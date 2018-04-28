(ns clj-http.test.client-test
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clj-http.conn-mgr :as conn]
            [clj-http.test.core-test :refer [run-server localhost]]
            [clj-http.util :as util]
            [clojure.string :as str]
            [clojure.java.io :refer [resource]]
            [clojure.test :refer :all]
            [cognitect.transit :as transit]
            [ring.util.codec :refer [form-decode-str]]
            [ring.middleware.nested-params :refer [parse-nested-keys]])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)
           (java.net UnknownHostException)
           (org.apache.hc.core5.http HttpEntity)
           (org.apache.logging.log4j LogManager)))

(defonce logger (LogManager/getLogger "clj-http.test.client-test"))

(def base-req
  {:scheme :http
   :server-name "localhost"
   :server-port 18080})

(defn request
  ([req]
   (client/request (merge base-req req)))
  ([req respond raise]
   (client/request (merge base-req req) respond raise)))

(defn parse-form-params [s]
  (->> (str/split (form-decode-str s) #"&")
       (map #(str/split % #"="))
       (map #(vector
              (map keyword (parse-nested-keys (first %)))
              (second %)))
       (reduce (fn [m [ks v]]
                 (assoc-in m ks v)) {})))

(deftest ^:integration roundtrip
  (run-server)
  ;; roundtrip with scheme as a keyword
  (let [resp (request {:uri "/get" :method :get})]
    (is (= 200 (:status resp)))
    (is (= "close" (get-in resp [:headers "connection"])))
    (is (= "get" (:body resp))))
  ;; roundtrip with scheme as a string
  (let [resp (request {:uri "/get" :method :get
                       :scheme "http"})]
    (is (= 200 (:status resp)))
    (is (= "close" (get-in resp [:headers "connection"])))
    (is (= "get" (:body resp))))
  (let [params {:a "1" :b "2"}]
    (doseq [[content-type read-fn]
            [[nil (comp parse-form-params slurp)]
             [:x-www-form-urlencoded (comp parse-form-params slurp)]
             [:edn (comp read-string slurp)]
             [:transit+json #(client/parse-transit % :json)]
             [:transit+msgpack #(client/parse-transit % :msgpack)]]]
      (let [resp (request {:uri "/post"
                           :as :stream
                           :method :post
                           :content-type content-type
                           :form-params params})]
        (is (= 200 (:status resp)))
        (is (= "close" (get-in resp [:headers "connection"])))
        (is (= params (read-fn (:body resp)))
            (str "failed with content-type [" content-type "]"))))))

(deftest ^:integration roundtrip-async
  (run-server)
  ;; roundtrip with scheme as a keyword
  (let [resp (promise)
        exception (promise)
        _ (request {:uri "/get" :method :get
                    :async? true} resp exception)]
    (is (= 200 (:status @resp)))
    (is (= "close" (get-in @resp [:headers "connection"])))
    (is (= "get" (:body @resp)))
    (is (not (realized? exception))))
  ;; roundtrip with scheme as a string
  (let [resp (promise)
        exception (promise)
        _ (request {:uri "/get" :method :get
                    :scheme "http"
                    :async? true} resp exception)]
    (is (= 200 (:status @resp)))
    (is (= "close" (get-in @resp [:headers "connection"])))
    (is (= "get" (:body @resp)))
    (is (not (realized? exception))))

  (let [params {:a "1" :b "2"}]
    (doseq [[content-type read-fn]
            [[nil (comp parse-form-params slurp)]
             [:x-www-form-urlencoded (comp parse-form-params slurp)]
             [:edn (comp read-string slurp)]
             [:transit+json #(client/parse-transit % :json)]
             [:transit+msgpack #(client/parse-transit % :msgpack)]]]
      (let [resp (promise)
            exception (promise)
            _ (request {:uri "/post"
                        :as :stream
                        :method :post
                        :content-type content-type
                        :flatten-nested-keys []
                        :form-params params
                        :async? true} resp exception)]
        (is (= 200 (:status @resp)))
        (is (= "close" (get-in @resp [:headers "connection"])))
        (is (= params (read-fn (:body @resp))))
        (is (not (realized? exception)))))))

(deftest ^:integration multipart-async
  (run-server)
  (let [resp (promise)
        exception (promise)
        _ (request {:uri "/post" :method :post
                    :async? true
                    :multipart [{:name "title" :content "some-file"}
                                {:name "Content/Type" :content "text/plain"}
                                {:name "file"
                                 :content (clojure.java.io/file
                                           "test-resources/m.txt")}]}
                   resp
                   exception)]
    (is (= 200 (:status @resp)))
    (is (not (realized? exception)))
    #_(when (realized? exception) (prn @exception))))

(deftest ^:integration nil-input
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
                        (client/get nil)))
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
                        (client/post nil)))
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
                        (client/put nil)))
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
                        (client/delete nil))))

(defn async-identity-client
  "A async client which simply respond the request"
  [request respond raise]
  (respond request))

(defn is-passed [middleware req]
  (let [client (middleware identity)]
    (is (= req (client req)))))

(defn is-passed-async [middleware req]
  (let [client (middleware async-identity-client)
        resp (promise)
        exception (promise)
        _ (client req resp exception)]
    (is (= req @resp))
    (is (not (realized? exception)))))

(defn is-applied [middleware req-in req-out]
  (let [client (middleware identity)]
    (is (= req-out (client req-in)))))

(defn is-applied-async [middleware req-in req-out]
  (let [client (middleware async-identity-client)
        resp (promise)
        exception (promise)
        _ (client req-in resp exception)]
    (is (= req-out @resp))
    (is (not (realized? exception)))))

(deftest throw-on-exceptional
  (let [client (fn [req] {:status 500})
        e-client (client/wrap-exceptions client)]
    (is (thrown-with-msg? Exception #"500"
                          (e-client {}))))
  (let [client (fn [req] {:status 500 :body "foo"})
        e-client (client/wrap-exceptions client)]
    (is (thrown-with-msg? Exception #":body"
                          (e-client {:throw-entire-message? true})))))

(deftest throw-type-field
  (let [client (fn [req] {:status 500})
        e-client (client/wrap-exceptions client)]
    (try
      (e-client {})
      (catch Exception e
        (if (= :clj-http.client/unexceptional-status (:type (ex-data e)))
          (is true)
          (is false ":type selector was not caught."))))))

(deftest throw-on-exceptional-async
  (let [client (fn [req respond raise]
                 (try
                   (respond {:status 500})
                   (catch Throwable ex
                     (raise ex))))
        e-client (client/wrap-exceptions client)
        resp (promise)
        exception (promise)
        _ (e-client {} resp exception)]
    (is (thrown-with-msg? Exception #"500"
                          (throw @exception))))
  (let [client (fn [req respond raise]
                 (try
                   (respond {:status 500 :body "foo"})
                   (catch Throwable ex
                     (raise ex))))
        e-client (client/wrap-exceptions client)
        resp (promise)
        exception (promise)
        _ (e-client {:throw-entire-message? true} resp exception)]
    (is (thrown-with-msg? Exception #":body"
                          (throw @exception)))))

(deftest pass-on-non-exceptional
  (let [client (fn [req] {:status 200})
        e-client (client/wrap-exceptions client)
        resp (e-client {})]
    (is (= 200 (:status resp)))))

(deftest pass-on-non-exceptional-async
  (let [client (fn [req respond raise] (respond {:status 200}))
        e-client (client/wrap-exceptions client)
        resp (promise)
        exception (promise)
        _ (e-client {} resp exception)]
    (is (= 200 (:status @resp)))
    (is (not (realized? exception)))))

(deftest pass-on-exceptional-when-surpressed
  (let [client (fn [req] {:status 500})
        e-client (client/wrap-exceptions client)
        resp (e-client {:throw-exceptions false})]
    (is (= 500 (:status resp)))))

(deftest pass-on-exceptional-when-surpressed-async
  (let [client (fn [req respond raise] (respond {:status 500}))
        e-client (client/wrap-exceptions client)
        resp (promise)
        exception (promise)
        _ (e-client {:throw-exceptions false} resp exception)]
    (is (= 500 (:status @resp)))
    (is (not (realized? exception)))))

(deftest apply-on-compressed
  (let [client (fn [req]
                 (is (= "gzip, deflate"
                        (get-in req [:headers "accept-encoding"])))
                 {:body (util/gzip (util/utf8-bytes "foofoofoo"))
                  :headers {"content-encoding" "gzip"}})
        c-client (client/wrap-decompression client)
        resp (c-client {})]
    (is (= "foofoofoo" (util/utf8-string (:body resp))))
    (is (= "gzip" (:orig-content-encoding resp)))
    (is (= nil (get-in resp [:headers "content-encoding"])))))

(deftest apply-on-compressed-async
  (let [client (fn [req respond raise]
                 (is (= "gzip, deflate"
                        (get-in req [:headers "accept-encoding"])))
                 (respond {:body (util/gzip (util/utf8-bytes "foofoofoo"))
                           :headers {"content-encoding" "gzip"}}))
        c-client (client/wrap-decompression client)
        resp (promise)
        exception (promise)
        _ (c-client {} resp exception)]
    (is (= "foofoofoo" (util/utf8-string (:body @resp))))
    (is (= "gzip" (:orig-content-encoding @resp)))
    (is (= nil (get-in @resp [:headers "content-encoding"])))))

(deftest apply-on-deflated
  (let [client (fn [req]
                 (is (= "gzip, deflate"
                        (get-in req [:headers "accept-encoding"])))
                 {:body (util/deflate (util/utf8-bytes "barbarbar"))
                  :headers {"content-encoding" "deflate"}})
        c-client (client/wrap-decompression client)
        resp (c-client {})]
    (is (= "barbarbar" (-> resp :body util/force-byte-array util/utf8-string))
        "string correctly inflated")
    (is (= "deflate" (:orig-content-encoding resp)))
    (is (= nil (get-in resp [:headers "content-encoding"])))))

(deftest apply-on-deflated-async
  (let [client (fn [req respond raise]
                 (is (= "gzip, deflate"
                        (get-in req [:headers "accept-encoding"])))
                 (respond {:body (util/deflate (util/utf8-bytes "barbarbar"))
                           :headers {"content-encoding" "deflate"}}))
        c-client (client/wrap-decompression client)
        resp (promise)
        exception (promise)
        _ (c-client {} resp exception)]
    (is (= "barbarbar" (-> @resp :body util/force-byte-array util/utf8-string))
        "string correctly inflated")
    (is (= "deflate" (:orig-content-encoding @resp)))
    (is (= nil (get-in @resp [:headers "content-encoding"])))))

(deftest t-disabled-body-decompression
  (let [client (fn [req]
                 (is (not= "gzip, deflate"
                           (get-in req [:headers "accept-encoding"])))
                 {:body (util/deflate (util/utf8-bytes "barbarbar"))
                  :headers {"content-encoding" "deflate"}})
        c-client (client/wrap-decompression client)
        resp (c-client {:decompress-body false})]
    (is (= (slurp (util/inflate (util/deflate (util/utf8-bytes "barbarbar"))))
           (slurp (util/inflate (-> resp :body util/force-byte-array))))
        "string not inflated")
    (is (= nil (:orig-content-encoding resp)))
    (is (= "deflate" (get-in resp [:headers "content-encoding"])))))

(deftest t-weird-non-known-compression
  (let [client (fn [req]
                 (is (= "gzip, deflate"
                        (get-in req [:headers "accept-encoding"])))
                 {:body (util/utf8-bytes "foofoofoo")
                  :headers {"content-encoding" "pig-latin"}})
        c-client (client/wrap-decompression client)
        resp (c-client {})]
    (is (= "foofoofoo" (util/utf8-string (:body resp))))
    (is (= "pig-latin" (:orig-content-encoding resp)))
    (is (= "pig-latin" (get-in resp [:headers "content-encoding"])))))

(deftest pass-on-non-compressed
  (let [c-client (client/wrap-decompression (fn [req] {:body "foo"}))
        resp (c-client {:uri "/foo"})]
    (is (= "foo" (:body resp)))))

(deftest apply-on-accept
  (is-applied client/wrap-accept
              {:accept :json}
              {:headers {"accept" "application/json"}})
  (is-applied client/wrap-accept
              {:accept :transit+json}
              {:headers {"accept" "application/transit+json"}})
  (is-applied client/wrap-accept
              {:accept :transit+msgpack}
              {:headers {"accept" "application/transit+msgpack"}}))

(deftest apply-on-accept-async
  (is-applied-async client/wrap-accept
                    {:accept :json}
                    {:headers {"accept" "application/json"}})
  (is-applied-async client/wrap-accept
                    {:accept :transit+json}
                    {:headers {"accept" "application/transit+json"}})
  (is-applied-async client/wrap-accept
                    {:accept :transit+msgpack}
                    {:headers {"accept" "application/transit+msgpack"}}))

(deftest pass-on-no-accept
  (is-passed client/wrap-accept
             {:uri "/foo"}))

(deftest pass-on-no-accept-async
  (is-passed-async client/wrap-accept
                   {:uri "/foo"}))

(deftest apply-on-accept-encoding
  (is-applied client/wrap-accept-encoding
              {:accept-encoding [:identity :gzip]}
              {:headers {"accept-encoding" "identity, gzip"}}))

(deftest apply-custom-accept-encoding
  (testing "no custom encodings to accept"
    (is-applied (comp client/wrap-accept-encoding
                      client/wrap-decompression)
                {}
                {:headers {"accept-encoding" "gzip, deflate"}
                 :orig-content-encoding nil}))
  (testing "accept some custom encodings, but still include gzip and deflate"
    (is-applied (comp client/wrap-accept-encoding
                      client/wrap-decompression)
                {:accept-encoding [:foo :bar]}
                {:headers {"accept-encoding" "foo, bar, gzip, deflate"}
                 :orig-content-encoding nil}))
  (testing "accept some custom encodings, but exclude gzip and deflate"
    (is-applied (comp client/wrap-accept-encoding
                      client/wrap-decompression)
                {:accept-encoding [:foo :bar] :decompress-body false}
                {:headers {"accept-encoding" "foo, bar"}
                 :decompress-body false})))

(deftest pass-on-no-accept-encoding
  (is-passed client/wrap-accept-encoding
             {:uri "/foo"}))

(deftest apply-on-output-coercion
  (let [client (fn [req] {:body (util/utf8-bytes "foo")})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo"})]
    (is (= "foo" (:body resp)))))

(deftest apply-on-output-coercion-async
  (let [client (fn [req respond raise]
                 (respond {:body (util/utf8-bytes "foo")}))
        o-client (client/wrap-output-coercion client)
        resp (promise)
        exception (promise)
        _ (o-client {:uri "/foo"} resp exception)]
    (is (= "foo" (:body @resp)))
    (is (not (realized? exception)))))

(deftest pass-on-no-output-coercion
  (let [client (fn [req] {:body nil})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo"})]
    (is (nil? (:body resp))))
  (let [the-stream (ByteArrayInputStream. (byte-array []))
        client (fn [req] {:body the-stream})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo" :as :stream})]
    (is (= the-stream (:body resp))))
  (let [client (fn [req] {:body :thebytes})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo" :as :byte-array})]
    (is (= :thebytes (:body resp)))))

(deftest pass-on-no-output-coercion-async
  (let [client (fn [req] {:body nil})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo"})]
    (is (nil? (:body resp))))
  (let [the-stream (ByteArrayInputStream. (byte-array []))
        client (fn [req] {:body the-stream})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo" :as :stream})]
    (is (= the-stream (:body resp))))
  (let [client (fn [req] {:body :thebytes})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo" :as :byte-array})]
    (is (= :thebytes (:body resp)))))

(deftest apply-on-input-coercion
  (let [i-client (client/wrap-input-coercion identity)
        resp (i-client {:body "foo"})
        resp2 (i-client {:body "foo2" :body-encoding "ASCII"})
        data (slurp (.getContent ^HttpEntity (:body resp)))
        data2 (slurp (.getContent ^HttpEntity (:body resp2)))]
    (is (= "UTF-8" (:character-encoding resp)))
    (is (= "foo" data))
    (is (= "ASCII" (:character-encoding resp2)))
    (is (= "foo2" data2))))

(deftest apply-on-input-coercion-async
  (let [i-client (client/wrap-input-coercion (fn [request respond raise]
                                               (respond request)))
        resp (promise)
        _ (i-client {:body "foo"} resp nil)
        resp2 (promise)
        _ (i-client {:body "foo2" :body-encoding "ASCII"} resp2 nil)
        data (slurp (.getContent ^HttpEntity (:body @resp)))
        data2 (slurp (.getContent ^HttpEntity (:body @resp2)))]
    (is (= "UTF-8" (:character-encoding @resp)))
    (is (= "foo" data))
    (is (= "ASCII" (:character-encoding @resp2)))
    (is (= "foo2" data2))))

(deftest pass-on-no-input-coercion
  (is-passed client/wrap-input-coercion
             {:body nil}))

(deftest pass-on-no-input-coercion
  (is-passed-async client/wrap-input-coercion
                   {:body nil}))

(deftest no-length-for-input-stream
  (let [i-client (client/wrap-input-coercion identity)
        resp1 (i-client {:body (ByteArrayInputStream. (util/utf8-bytes "foo"))})
        resp2 (i-client {:body (ByteArrayInputStream. (util/utf8-bytes "foo"))
                         :length 3})
        ^HttpEntity body1 (:body resp1)
        ^HttpEntity body2 (:body resp2)]
    (is (= -1 (.getContentLength body1)))
    (is (= 3 (.getContentLength body2)))))

(deftest apply-on-content-type
  (is-applied client/wrap-content-type
              {:content-type :json}
              {:headers {"content-type" "application/json"}
               :content-type :json})
  (is-applied client/wrap-content-type
              {:content-type :json :character-encoding "UTF-8"}
              {:headers {"content-type" "application/json; charset=UTF-8"}
               :content-type :json :character-encoding "UTF-8"})
  (is-applied client/wrap-content-type
              {:content-type :transit+json}
              {:headers {"content-type" "application/transit+json"}
               :content-type :transit+json})
  (is-applied client/wrap-content-type
              {:content-type :transit+msgpack}
              {:headers {"content-type" "application/transit+msgpack"}
               :content-type :transit+msgpack}))

(deftest apply-on-content-type-async
  (is-applied-async client/wrap-content-type
                    {:content-type :json}
                    {:headers {"content-type" "application/json"}
                     :content-type :json})
  (is-applied-async client/wrap-content-type
                    {:content-type :json :character-encoding "UTF-8"}
                    {:headers {"content-type" "application/json; charset=UTF-8"}
                     :content-type :json :character-encoding "UTF-8"})
  (is-applied-async client/wrap-content-type
                    {:content-type :transit+json}
                    {:headers {"content-type" "application/transit+json"}
                     :content-type :transit+json})
  (is-applied-async client/wrap-content-type
                    {:content-type :transit+msgpack}
                    {:headers {"content-type" "application/transit+msgpack"}
                     :content-type :transit+msgpack}))

(deftest pass-on-no-content-type
  (is-passed client/wrap-content-type
             {:uri "/foo"}))

(deftest apply-on-query-params
  (is-applied client/wrap-query-params
              {:query-params {"foo" "bar" "dir" "<<"}}
              {:query-string "foo=bar&dir=%3C%3C"})
  (is-applied client/wrap-query-params
              {:query-string "foo=1"
               :query-params {"foo" ["2" "3"]}}
              {:query-string "foo=1&foo=2&foo=3"}))

(deftest apply-on-query-params-async
  (is-applied-async client/wrap-query-params
                    {:query-params {"foo" "bar" "dir" "<<"}}
                    {:query-string "foo=bar&dir=%3C%3C"})
  (is-applied-async client/wrap-query-params
                    {:query-string "foo=1"
                     :query-params {"foo" ["2" "3"]}}
                    {:query-string "foo=1&foo=2&foo=3"}))

(deftest pass-on-no-query-params
  (is-passed client/wrap-query-params
             {:uri "/foo"}))

(deftest apply-on-basic-auth
  (is-applied client/wrap-basic-auth
              {:basic-auth ["Aladdin" "open sesame"]}
              {:headers {"authorization"
                         "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ=="}}))

(deftest apply-on-basic-auth-async
  (is-applied-async client/wrap-basic-auth
                    {:basic-auth ["Aladdin" "open sesame"]}
                    {:headers {"authorization"
                               "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ=="}}))

(deftest pass-on-no-basic-auth
  (is-passed client/wrap-basic-auth
             {:uri "/foo"}))

(deftest apply-on-oauth
  (is-applied client/wrap-oauth
              {:oauth-token "my-token"}
              {:headers {"authorization"
                         "Bearer my-token"}}))

(deftest apply-on-oauth-async
  (is-applied-async client/wrap-oauth
                    {:oauth-token "my-token"}
                    {:headers {"authorization"
                               "Bearer my-token"}}))

(deftest pass-on-no-oauth
  (is-passed client/wrap-oauth
             {:uri "/foo"}))

(deftest apply-on-method
  (let [m-client (client/wrap-method identity)
        echo (m-client {:key :val :method :post})]
    (is (= :val (:key echo)))
    (is (= :post (:request-method echo)))
    (is (not (:method echo)))))

(deftest apply-on-method-async
  (let [m-client (client/wrap-method async-identity-client)
        echo (promise)
        exception (promise)
        _ (m-client {:key :val :method :post} echo exception)]
    (is (= :val (:key @echo)))
    (is (= :post (:request-method @echo)))
    (is (not (:method @echo)))))

(deftest pass-on-no-method
  (let [m-client (client/wrap-method identity)
        echo (m-client {:key :val})]
    (is (= :val (:key echo)))
    (is (not (:request-method echo)))))

(deftest apply-on-url
  (let [u-client (client/wrap-url identity)
        resp (u-client {:url "http://google.com:8080/baz foo?bar=bat bit?"})]
    (is (= :http (:scheme resp)))
    (is (= "google.com" (:server-name resp)))
    (is (= 8080 (:server-port resp)))
    (is (= "/baz%20foo" (:uri resp)))
    (is (= "bar=bat%20bit?" (:query-string resp)))))

(deftest apply-on-url
  (let [u-client (client/wrap-url async-identity-client)
        resp (promise)
        exception (promise)
        _ (u-client {:url "http://google.com:8080/baz foo?bar=bat bit?"}
                    resp exception)]
    (is (= :http (:scheme @resp)))
    (is (= "google.com" (:server-name @resp)))
    (is (= 8080 (:server-port @resp)))
    (is (= "/baz%20foo" (:uri @resp)))
    (is (= "bar=bat%20bit?" (:query-string @resp)))
    (is (not (realized? exception)))))

(deftest pass-on-no-url
  (let [u-client (client/wrap-url identity)
        resp (u-client {:uri "/foo"})]
    (is (= "/foo" (:uri resp)))))

(deftest provide-default-port
  (is (= nil  (-> "http://example.com/" client/parse-url :server-port)))
  (is (= 8080 (-> "http://example.com:8080/" client/parse-url :server-port)))
  (is (= nil  (-> "https://example.com/" client/parse-url :server-port)))
  (is (= 8443 (-> "https://example.com:8443/" client/parse-url :server-port)))
  (is (= "https://example.com:8443/"
         (-> "https://example.com:8443/" client/parse-url :url))))

(deftest decode-credentials-from-url
  (is (= "fred's diner:fred's password"
         (-> "http://fred%27s%20diner:fred%27s%20password@example.com/foo"
             client/parse-url
             :user-info))))

(deftest unparse-url
  (is (= "http://fred's diner:fred's password@example.com/foo"
         (-> "http://fred%27s%20diner:fred%27s%20password@example.com/foo"
             client/parse-url client/unparse-url)))
  (is (= "https://foo:bar@example.org:8080"
         (-> "https://foo:bar@example.org:8080"
             client/parse-url client/unparse-url)))
  (is (= "ftp://example.org?foo"
         (-> "ftp://example.org?foo"
             client/parse-url client/unparse-url))))

(defrecord Point [x y])

(def write-point
  "Write a point in Transit format."
  (transit/write-handler
   (constantly "point")
   (fn [point] [(:x point) (:y point)])
   (constantly nil)))

(def read-point
  "Read a point in Transit format."
  (transit/read-handler
   (fn [[x y]]
     (->Point x y))))

(def transit-opts
  "Transit read and write options."
  {:encode {:handlers {Point write-point}}
   :decode {:handlers {"point" read-point}}})

(def transit-opts-deprecated
  "Deprecated Transit read and write options."
  {:handlers {Point write-point "point" read-point}})

(deftest apply-on-form-params
  (testing "With form params"
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:request-method :post
                              :form-params (sorted-map :param1 "value1"
                                                       :param2 "value2")})]
      (is (= "param1=value1&param2=value2" (:body resp)))
      (is (= "application/x-www-form-urlencoded" (:content-type resp)))
      (is (not (contains? resp :form-params))))
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:request-method :put
                              :form-params (sorted-map :param1 "value1"
                                                       :param2 "value2")})]
      (is (= "param1=value1&param2=value2" (:body resp)))
      (is (= "application/x-www-form-urlencoded" (:content-type resp)))
      (is (not (contains? resp :form-params)))))

  (testing "With json form params"
    (let [param-client (client/wrap-form-params identity)
          params {:param1 "value1" :param2 "value2"}
          resp (param-client {:request-method :post
                              :content-type :json
                              :form-params params})]
      (is (= (json/encode params) (:body resp)))
      (is (= "application/json" (:content-type resp)))
      (is (not (contains? resp :form-params))))
    (let [param-client (client/wrap-form-params identity)
          params {:param1 "value1" :param2 "value2"}
          resp (param-client {:request-method :put
                              :content-type :json
                              :form-params params})]
      (is (= (json/encode params) (:body resp)))
      (is (= "application/json" (:content-type resp)))
      (is (not (contains? resp :form-params))))
    (let [param-client (client/wrap-form-params identity)
          params {:param1 "value1" :param2 "value2"}
          resp (param-client {:request-method :patch
                              :content-type :json
                              :form-params params})]
      (is (= (json/encode params) (:body resp)))
      (is (= "application/json" (:content-type resp)))
      (is (not (contains? resp :form-params))))
    (let [param-client (client/wrap-form-params identity)
          params {:param1 (java.util.Date. (long 0))}
          resp (param-client {:request-method :put
                              :content-type :json
                              :form-params params
                              :json-opts {:date-format "yyyy-MM-dd"}})]
      (is (= (json/encode params {:date-format "yyyy-MM-dd"}) (:body resp)))
      (is (= "application/json" (:content-type resp)))
      (is (not (contains? resp :form-params)))))

  (testing "With EDN form params"
    (doseq [method [:post :put :patch]]
      (let [param-client (client/wrap-form-params identity)
            params {:param1 "value1" :param2 (Point. 1 2)}
            resp (param-client {:request-method method
                                :content-type :edn
                                :form-params params})]
        (is (= (pr-str params) (:body resp)))
        (is (= "application/edn" (:content-type resp)))
        (is (not (contains? resp :form-params))))))

  (testing "With Transit/JSON form params"
    (doseq [method [:post :put :patch]]
      (let [param-client (client/wrap-form-params identity)
            params {:param1 "value1" :param2 (Point. 1 2)}
            resp (param-client {:request-method method
                                :content-type :transit+json
                                :form-params params
                                :transit-opts transit-opts})]
        (is (= params (client/parse-transit
                       (ByteArrayInputStream. (:body resp))
                       :json transit-opts)))
        (is (= "application/transit+json" (:content-type resp)))
        (is (not (contains? resp :form-params))))))

  (testing "With Transit/MessagePack form params"
    (doseq [method [:post :put :patch]]
      (let [param-client (client/wrap-form-params identity)
            params {:param1 "value1" :param2 "value2"}
            resp (param-client {:request-method method
                                :content-type :transit+msgpack
                                :form-params params
                                :transit-opts transit-opts})]
        (is (= params (client/parse-transit
                       (ByteArrayInputStream. (:body resp))
                       :msgpack transit-opts)))
        (is (= "application/transit+msgpack" (:content-type resp)))
        (is (not (contains? resp :form-params))))))

  (testing "With Transit/JSON form params and deprecated options"
    (let [param-client (client/wrap-form-params identity)
          params {:param1 "value1" :param2 (Point. 1 2)}
          resp (param-client {:request-method :post
                              :content-type :transit+json
                              :form-params params
                              :transit-opts transit-opts-deprecated})]
      (is (= params (client/parse-transit
                     (ByteArrayInputStream. (:body resp))
                     :json transit-opts-deprecated)))
      (is (= "application/transit+json" (:content-type resp)))
      (is (not (contains? resp :form-params)))))

  (testing "Ensure it does not affect GET requests"
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:request-method :get
                              :body "untouched"
                              :form-params {:param1 "value1"
                                            :param2 "value2"}})]
      (is (= "untouched" (:body resp)))
      (is (not (contains? resp :content-type)))))

  (testing "with no form params"
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:body "untouched"})]
      (is (= "untouched" (:body resp)))
      (is (not (contains? resp :content-type))))))

(deftest apply-on-form-params-async
  (testing "With form params"
    (let [param-client (client/wrap-form-params async-identity-client)
          resp (promise)
          exception (promise)
          _ (param-client {:request-method :post
                           :form-params (sorted-map :param1 "value1"
                                                    :param2 "value2")}
                          resp exception)]
      (is (= "param1=value1&param2=value2" (:body @resp)))
      (is (= "application/x-www-form-urlencoded" (:content-type @resp)))
      (is (not (contains? @resp :form-params)))
      (is (not (realized? exception))))
    (let [param-client (client/wrap-form-params async-identity-client)
          resp (promise)
          exception (promise)
          _ (param-client {:request-method :put
                           :form-params (sorted-map :param1 "value1"
                                                    :param2 "value2")}
                          resp exception)]
      (is (= "param1=value1&param2=value2" (:body @resp)))
      (is (= "application/x-www-form-urlencoded" (:content-type @resp)))
      (is (not (contains? @resp :form-params)))
      (is (not (realized? exception)))))

  (testing "Ensure it does not affect GET requests"
    (let [param-client (client/wrap-form-params async-identity-client)
          resp (promise)
          exception (promise)
          _ (param-client {:request-method :get
                           :body "untouched"
                           :form-params {:param1 "value1"
                                         :param2 "value2"}}
                          resp exception)]
      (is (= "untouched" (:body @resp)))
      (is (not (contains? @resp :content-type)))
      (is (not (realized? exception)))))

  (testing "with no form params"
    (let [param-client (client/wrap-form-params async-identity-client)
          resp (promise)
          exception (promise)
          _ (param-client {:body "untouched"} resp exception)]
      (is (= "untouched" (:body @resp)))
      (is (not (contains? @resp :content-type)))
      (is (not (realized? exception))))))

(deftest apply-on-nested-params
  (testing "nested parameter maps"
    (is-applied (comp client/wrap-form-params
                      client/wrap-nested-params)
                {:query-params {"foo" "bar"}
                 :form-params {"foo" "bar"}
                 :flatten-nested-keys [:query-params :form-params]}
                {:query-params {"foo" "bar"}
                 :form-params {"foo" "bar"}
                 :flatten-nested-keys [:query-params :form-params]})
    (is-applied (comp client/wrap-form-params
                      client/wrap-nested-params)
                {:query-params {"x" {"y" "z"}}
                 :form-params {"x" {"y" "z"}}
                 :flatten-nested-keys [:query-params]}
                {:query-params {"x[y]" "z"}
                 :form-params {"x" {"y" "z"}}
                 :flatten-nested-keys [:query-params]})
    (is-applied (comp client/wrap-form-params
                      client/wrap-nested-params)
                {:query-params {"a" {"b" {"c" "d"}}}
                 :form-params {"a" {"b" {"c" "d"}}}
                 :flatten-nested-keys [:form-params]}
                {:query-params {"a" {"b" {"c" "d"}}}
                 :form-params {"a[b][c]" "d"}
                 :flatten-nested-keys [:form-params]})
    (is-applied (comp client/wrap-form-params
                      client/wrap-nested-params)
                {:query-params {"a" {"b" {"c" "d"}}}
                 :form-params {"a" {"b" {"c" "d"}}}
                 :flatten-nested-keys [:query-params :form-params]}
                {:query-params {"a[b][c]" "d"}
                 :form-params {"a[b][c]" "d"}
                 :flatten-nested-keys [:query-params :form-params]}))

  (testing "not creating empty param maps"
    (is-applied client/wrap-query-params {} {})))

(deftest t-ignore-unknown-host
  (is (thrown? UnknownHostException (client/get "http://example.invalid")))
  (is (nil? (client/get "http://example.invalid"
                        {:ignore-unknown-host? true}))))

(deftest t-ignore-unknown-host-async
  (let [resp (promise) exception (promise)]
    (client/get "http://example.invalid"
                {:async? true} resp exception)
    (is (thrown? UnknownHostException (throw @exception))))
  (let [resp (promise) exception (promise)]
    (client/get "http://example.invalid"
                {:ignore-unknown-host? true
                 :async? true} resp exception)
    (is (nil? @resp))))

(deftest test-status-predicates
  (testing "2xx statuses"
    (doseq [s (range 200 299)]
      (is (client/success? {:status s}))
      (is (not (client/redirect? {:status s})))
      (is (not (client/client-error? {:status s})))
      (is (not (client/server-error? {:status s})))))
  (testing "3xx statuses"
    (doseq [s (range 300 399)]
      (is (not (client/success? {:status s})))
      (is (client/redirect? {:status s}))
      (is (not (client/client-error? {:status s})))
      (is (not (client/server-error? {:status s})))))
  (testing "4xx statuses"
    (doseq [s (range 400 499)]
      (is (not (client/success? {:status s})))
      (is (not (client/redirect? {:status s})))
      (is (client/client-error? {:status s}))
      (is (not (client/server-error? {:status s})))))
  (testing "5xx statuses"
    (doseq [s (range 500 599)]
      (is (not (client/success? {:status s})))
      (is (not (client/redirect? {:status s})))
      (is (not (client/client-error? {:status s})))
      (is (client/server-error? {:status s}))))
  (testing "409 Conflict"
    (is (client/conflict? {:status 409}))
    (is (not (client/conflict? {:status 201})))
    (is (not (client/conflict? {:status 404})))))

(deftest test-wrap-lower-case-headers
  (is (= {:status 404} ((client/wrap-lower-case-headers
                         (fn [r] r)) {:status 404})))
  (is (= {:headers {"content-type" "application/json"}}
         ((client/wrap-lower-case-headers
           #(do (is (= {:headers {"accept" "application/json"}} %1))
                {:headers {"Content-Type" "application/json"}}))
          {:headers {"Accept" "application/json"}}))))

(deftest t-request-timing
  (is (pos? (:request-time ((client/wrap-request-timing
                             (fn [r] (Thread/sleep 15) r)) {})))))

(deftest t-wrap-additional-header-parsing
  (let [^String text (slurp (resource "header-test.html"))
        client (fn [req] {:body (.getBytes text)})
        new-client (client/wrap-additional-header-parsing client)
        resp (new-client {:decode-body-headers true})
        resp2 (new-client {:decode-body-headers false})
        resp3 ((client/wrap-additional-header-parsing
                (fn [req] {:body nil})) {:decode-body-headers true})
        resp4 ((client/wrap-additional-header-parsing
                (fn [req] {:headers {"content-type" "application/pdf"}
                           :body (.getBytes text)}))
               {:decode-body-headers true})]
    (is (= {"content-type" "text/html; charset=Shift_JIS"
            "content-style-type" "text/css"
            "content-script-type" "text/javascript"}
           (:headers resp)))
    (is (nil? (:headers resp2)))
    (is (nil? (:headers resp3)))
    (is (= {"content-type" "application/pdf"} (:headers resp4)))))

(deftest t-wrap-additional-header-parsing-html5
  (let [^String text (slurp (resource "header-html5-test.html"))
        client (fn [req] {:body (.getBytes text)})
        new-client (client/wrap-additional-header-parsing client)
        resp (new-client {:decode-body-headers true})]
    (is (= {"content-type" "text/html; charset=UTF-8"}
           (:headers resp)))))

(deftest ^:integration t-request-without-url-set
  (run-server)
  ;; roundtrip with scheme as a keyword
  (let [resp (request {:uri "/redirect-to-get"
                       :method :get})]
    (is (= 200 (:status resp)))
    (is (= "close" (get-in resp [:headers "connection"])))
    (is (= "get" (:body resp)))))

(deftest ^:integration t-reusable-conn-mgrs
  (run-server)
  (let [cm (conn/make-reusable-conn-manager {:timeout 10 :insecure? false})
        resp1 (request {:uri "/redirect-to-get"
                        :method :get
                        :connection-manager cm})
        resp2 (request {:uri "/redirect-to-get"
                        :method :get})]
    (is (= 200 (:status resp1) (:status resp2)))
    (is (nil? (get-in resp1 [:headers "connection"]))
        "connection should remain open")
    (is (= "close" (get-in resp2 [:headers "connection"]))
        "connection should be closed")
    (conn/shutdown-manager cm)))

(deftest ^:integration t-reusable-async-conn-mgrs
  (run-server)
  (let [cm (conn/make-reuseable-async-conn-manager {:timeout 10 :insecure? false})
        resp1 (promise) resp2 (promise)
        exce1 (promise) exce2 (promise)]
    (request {:async? true :uri "/redirect-to-get" :method :get :connection-manager cm}
             resp1
             exce1)
    (request {:async? true :uri "/redirect-to-get" :method :get}
             resp2
             exce2)
    (is (= 200 (:status @resp1) (:status @resp2)))
    (is (nil? (get-in @resp1 [:headers "connection"]))
        "connection should remain open")
    (is (= "close" (get-in @resp2 [:headers "connection"]))
        "connection should be closed")
    (is (not (realized? exce2)))
    (is (not (realized? exce1)))
    (conn/shutdown-manager cm)))

(deftest ^:integration t-with-async-pool
  (run-server)
  (client/with-async-connection-pool {}
    (let [resp1 (promise) resp2 (promise)
          exce1 (promise) exce2 (promise)]
      (request {:async? true :uri "/get" :method :get} resp1 exce1)
      (request {:async? true :uri "/get" :method :get} resp2 exce2)
      (is (= 200 (:status @resp1) (:status @resp2)))
      (is (not (realized? exce2)))
      (is (not (realized? exce1))))))

(deftest ^:integration t-with-async-pool-sleep
  (run-server)
  (client/with-async-connection-pool {}
    (let [resp1 (promise) resp2 (promise)
          exce1 (promise) exce2 (promise)]
      (request {:async? true :uri "/get" :method :get} resp1 exce1)
      (Thread/sleep 500)
      (request {:async? true :uri "/get" :method :get} resp2 exce2)
      (is (= 200 (:status @resp1) (:status @resp2)))
      (is (not (realized? exce2)))
      (is (not (realized? exce1))))))

(deftest ^:integration t-async-pool-wrap-exception
  (run-server)
  (client/with-async-connection-pool {}
    (let [resp1 (promise) resp2 (promise)
          exce1 (promise) exce2 (promise) count (atom 2)]
      (request {:async? true :uri "/error" :method :get} resp1 exce1)
      (Thread/sleep 500)
      (request {:async? true :uri "/get" :method :get} resp2 exce2)
      (is (realized? exce1))
      (is (not (realized? exce2)))
      (is (= 200 (:status @resp2))))))

(deftest ^:integration t-async-pool-exception-when-start
  (run-server)
  (client/with-async-connection-pool {}
    (let [resp1 (promise) resp2 (promise)
          exce1 (promise) exce2 (promise)
          middleware (fn [client]
                       (fn [req resp raise] (throw (Exception.))))]
      (client/with-additional-middleware
        [middleware]
        (try (request {:async? true :uri "/error" :method :get} resp1 exce1)
             (catch Throwable ex))
        (Thread/sleep 500)
        (try (request {:async? true :uri "/get" :method :get} resp2 exce2)
             (catch Throwable ex))
        (is (not (realized? exce1)))
        (is (not (realized? exce2)))
        (is (not (realized? resp1)))
        (is (not (realized? resp2)))))))

(deftest ^:integration t-reuse-async-pool
  (run-server)
  (client/with-async-connection-pool {}
    (let [resp1 (promise) resp2 (promise)
          exce1 (promise) exce2 (promise)]
      (request {:async? true :uri "/get" :method :get}
               (fn [resp]
                 (resp1 resp)
                 (request (client/reuse-pool
                           {:async? true
                            :uri "/get"
                            :method :get}
                           resp)
                          resp2
                          exce2))
               exce1)
      (is (= 200 (:status @resp1) (:status @resp2)))
      (is (not (realized? exce2)))
      (is (not (realized? exce1))))))

(deftest ^:integration t-async-pool-redirect-to-get
  (run-server)
  (client/with-async-connection-pool {}
    (let [resp (promise)
          exce (promise)]
      (request {:async? true :uri "/redirect-to-get"
                :method :get :redirect-strategy :default} resp exce)
      (is (= 200 (:status @resp)))
      (is (not (realized? exce))))))

(deftest ^:integration t-async-pool-max-redirect
  (run-server)
  (client/with-async-connection-pool {}
    (let [resp (promise)
          exce (promise)]
      (request {:async? true :uri "/redirect" :method :get
                :redirect-strategy :default
                :throw-exceptions true} resp exce)
      (is @exce)
      (is (not (realized? resp))))))

(deftest test-url-encode-path
  (is (= (client/url-encode-illegal-characters "?foo bar+baz[]75")
         "?foo%20bar+baz%5B%5D75"))
  (is (= {:uri (str "/:@-._~!$&'()*+,="
                    ";"
                    ":@-._~!$&'()*+,"
                    "="
                    ":@-._~!$&'()*+,==")
          :query-string (str "/?:@-._~!$'()*+,;"
                             "="
                             "/?:@-._~!$'()*+,;==")}
         ;; This URL sucks, yes, it's actually a valid URL
         (select-keys (client/parse-url
                       (str "http://example.com/:@-._~!$&'()*+,=;:@-._~!$&'()*+"
                            ",=:@-._~!$&'()*+,==?/?:@-._~!$'()*+,;=/?:@-._~!$'("
                            ")*+,;==#/?:@-._~!$&'()*+,;="))
                      [:uri :query-string])))
  (let [all-chars (apply str (map char (range 256)))
        all-legal (client/url-encode-illegal-characters all-chars)]
    (is (= all-legal
           (client/url-encode-illegal-characters all-legal)))))

(deftest t-coercion-methods
  (let [json-body (ByteArrayInputStream. (.getBytes "{\"foo\":\"bar\"}"))
        auto-body (ByteArrayInputStream. (.getBytes "{\"foo\":\"bar\"}"))
        edn-body (ByteArrayInputStream. (.getBytes "{:foo \"bar\"}"))
        transit-json-body (ByteArrayInputStream.
                           (.getBytes "[\"^ \",\"~:foo\",\"bar\"]"))
        transit-msgpack-body (->> (map byte [-127 -91 126 58 102 111
                                             111 -93 98 97 114])
                                  (byte-array 11)
                                  (ByteArrayInputStream.))
        www-form-urlencoded-body (ByteArrayInputStream. (.getBytes "foo=bar"))
        auto-www-form-urlencoded-body
        (ByteArrayInputStream. (.getBytes "foo=bar"))
        json-resp {:body json-body :status 200
                   :headers {"content-type" "application/json"}}
        auto-resp {:body auto-body :status 200
                   :headers {"content-type" "application/json"}}
        edn-resp {:body edn-body :status 200
                  :headers {"content-type" "application/edn"}}
        transit-json-resp {:body transit-json-body :status 200
                           :headers {"content-type" "application/transit-json"}}
        transit-msgpack-resp {:body transit-msgpack-body :status 200
                              :headers {"content-type"
                                        "application/transit-msgpack"}}
        www-form-urlencoded-resp
        {:body www-form-urlencoded-body :status 200
         :headers {"content-type"
                   "application/x-www-form-urlencoded"}}
        auto-www-form-urlencoded-resp
        {:body auto-www-form-urlencoded-body :status 200
         :headers {"content-type"
                   "application/x-www-form-urlencoded"}}]
    (is (= {:foo "bar"}
           (:body (client/coerce-response-body {:as :json} json-resp))
           (:body (client/coerce-response-body {:as :clojure} edn-resp))
           (:body (client/coerce-response-body {:as :auto} auto-resp))
           (:body (client/coerce-response-body {:as :transit+json}
                                               transit-json-resp))
           (:body (client/coerce-response-body {:as :transit+msgpack}
                                               transit-msgpack-resp))
           (:body (client/coerce-response-body {:as :auto}
                                               auto-www-form-urlencoded-resp))
           (:body (client/coerce-response-body {:as :x-www-form-urlencoded}
                                               www-form-urlencoded-resp))))))

(deftest ^:integration t-with-middleware
  (run-server)
  (is (:request-time (request {:uri "/get" :method :get})))
  (is (= client/*current-middleware* client/default-middleware))
  (client/with-middleware [client/wrap-url
                           client/wrap-method
                           #'client/wrap-request-timing]
    (is (:request-time (request {:uri "/get" :method :get})))
    (is (= client/*current-middleware* [client/wrap-url
                                        client/wrap-method
                                        #'client/wrap-request-timing])))
  (client/with-middleware (->> client/default-middleware
                               (remove #{client/wrap-request-timing}))
    (is (not (:request-time (request {:uri "/get" :method :get}))))
    (is (not (contains? (set client/*current-middleware*)
                        client/wrap-request-timing)))
    (is (contains? (set client/default-middleware)
                   client/wrap-request-timing))))

(deftest t-detect-charset-by-content-type
  (is (= "UTF-8" (client/detect-charset nil)))
  (is (= "UTF-8"(client/detect-charset "application/json")))
  (is (= "UTF-8"(client/detect-charset "text/html")))
  (is (= "GBK"(client/detect-charset "application/json; charset=GBK")))
  (is (= "ISO-8859-1" (client/detect-charset
                       "application/json; charset=ISO-8859-1")))
  (is (= "ISO-8859-1" (client/detect-charset
                       "application/json; charset =  ISO-8859-1")))
  (is (= "GB2312" (client/detect-charset "text/html; Charset=GB2312"))))

(deftest ^:integration customMethodTest
  (run-server)
  (let [resp (request {:uri "/propfind" :method "PROPFIND"})]
    (is (= 200 (:status resp)))
    (is (= "close" (get-in resp [:headers "connection"])))
    (is (= "propfind" (:body resp))))
  (let [resp (request {:uri "/propfind-with-body"
                       :method "PROPFIND"
                       :body "propfindbody"})]
    (is (= 200 (:status resp)))
    (is (= "close" (get-in resp [:headers "connection"])))
    (is (= "propfindbody" (:body resp)))))

(deftest ^:integration status-line-parsing
  (run-server)
  (let [resp (request {:uri "/get" :method :get})
        protocol-version (:protocol-version resp)]
    (is (= 200 (:status resp)))
    (is (= "HTTP" (:name protocol-version)))
    (is (= 1 (:major protocol-version)))
    (is (= 1 (:minor protocol-version)))
    (is (= "OK" (:reason-phrase resp)))))

(deftest ^:integration multi-valued-query-params
  (run-server)
  (testing "default (repeating) multi-valued query params"
    (let [resp (request {:uri "/query-string"
                         :method :get
                         :query-params {:a [1 2 3]
                                        :b ["x" "y" "z"]}})
          query-string (-> resp :body form-decode-str)]
      (is (= 200 (:status resp)))
      (is (.contains query-string "a=1&a=2&a=3") query-string)
      (is (.contains query-string "b=x&b=y&b=z") query-string)))

  (testing "multi-valued query params in indexed-style"
    (let [resp (request {:uri "/query-string"
                         :method :get
                         :multi-param-style :indexed
                         :query-params {:a [1 2 3]
                                        :b ["x" "y" "z"]}})
          query-string (-> resp :body form-decode-str)]
      (is (= 200 (:status resp)))
      (is (.contains query-string "a[0]=1&a[1]=2&a[2]=3") query-string)
      (is (.contains query-string "b[0]=x&b[1]=y&b[2]=z") query-string)))

  (testing "multi-valued query params in array-style"
    (let [resp (request {:uri "/query-string"
                         :method :get
                         :multi-param-style :array
                         :query-params {:a [1 2 3]
                                        :b ["x" "y" "z"]}})
          query-string (-> resp :body form-decode-str)]
      (is (= 200 (:status resp)))
      (is (.contains query-string "a[]=1&a[]=2&a[]=3") query-string)
      (is (.contains query-string "b[]=x&b[]=y&b[]=z") query-string))))

(deftest t-wrap-flatten-nested-params
  (is-applied client/wrap-flatten-nested-params
              {}
              {:flatten-nested-keys [:query-params]})
  (is-applied client/wrap-flatten-nested-params
              {:flatten-nested-keys []}
              {:flatten-nested-keys []})
  (is-applied client/wrap-flatten-nested-params
              {:flatten-nested-keys [:foo]}
              {:flatten-nested-keys [:foo]})
  (is-applied client/wrap-flatten-nested-params
              {:ignore-nested-query-string true}
              {:ignore-nested-query-string true
               :flatten-nested-keys []})
  (is-applied client/wrap-flatten-nested-params
              {}
              {:flatten-nested-keys '(:query-params)})
  (is-applied client/wrap-flatten-nested-params
              {:flatten-nested-form-params true}
              {:flatten-nested-form-params true
               :flatten-nested-keys '(:query-params :form-params)})
  (is-applied client/wrap-flatten-nested-params
              {:flatten-nested-form-params true
               :ignore-nested-query-string true}
              {:ignore-nested-query-string true
               :flatten-nested-form-params true
               :flatten-nested-keys '(:form-params)})
  (try
    ((client/wrap-flatten-nested-params identity)
     {:flatten-nested-form-params true
      :ignore-nested-query-string true
      :flatten-nested-keys [:thing :bar]})
    (is false "should have thrown exception")
    (catch IllegalArgumentException e
      (is (= (.getMessage e)
             (str "only :flatten-nested-keys or :ignore-nested-query-string/"
                  ":flatten-nested-keys may be specified, not both")))))
  (try
    ((client/wrap-flatten-nested-params identity)
     {:ignore-nested-query-string true
      :flatten-nested-keys [:thing :bar]})
    (is false "should have thrown exception")
    (catch IllegalArgumentException e
      (is (= (.getMessage e)
             (str "only :flatten-nested-keys or :ignore-nested-query-string/"
                  ":flatten-nested-keys may be specified, not both"))))))

(deftest ^:integration t-socket-capture
  (run-server)
  (let [resp (client/post (localhost "/post")
                          {:capture-socket true
                           :body "This is a test"
                           :headers {"content-type" "application/fun"}})
        raw (:raw-socket-str resp)
        test-fn (fn [raw]
                  (is (.contains raw "POST /post HTTP/1.1"))
                  (is (.contains raw "Connection: close"))
                  (is (.contains raw "content-type: application/fun"))
                  (is (.contains raw "accept-encoding: gzip, deflate"))
                  (is (.contains raw "Content-Length: 14"))
                  (is (.contains raw "Host: ")) ;; Host might not be "localhost"
                  ;; Might be a different java version
                  (is (.contains raw "User-Agent: Apache-HttpClient/4"))
                  (is (.contains raw "\r\n\r\nThis is a test")))]
    (is (= 200 (:status resp)))
    (is (= "close" (get-in resp [:headers "connection"])))
    (test-fn (:raw-socket-str resp))
    (test-fn (String. (:raw-socket-bytes resp) StandardCharsets/UTF_8)))
  (testing "failure cases"
    (try
      (client/get (localhost "/get")
                  {:capture-socket true
                   :insecure true})
      (is false "should have failed")
      (catch Exception e
        (is (.contains
             (.getMessage e)
             (str "capturing sockets cannot be used with custom or "
                  "insecure connection manager")))))
    (try
      (client/get (localhost "/get")
                  {:capture-socket true
                   :connection-manager 'foo})
      (is false "should have failed")
      (catch Exception e
        (is (.contains
             (.getMessage e)
             (str "capturing sockets cannot be used with custom or "
                  "insecure connection manager")))))
    (try
      (binding [conn/*connection-manager* 'foo]
        (client/get (localhost "/get") {:capture-socket true}))
      (is false "should have failed")
      (catch Exception e
        (is (.contains
             (.getMessage e)
             (str "capturing sockets cannot be used with custom or "
                  "insecure connection manager")))))
    (try
      (client/get (localhost "/get") {:capture-socket true :async true}
                  identity identity)
      (is false "should have failed")
      (catch Exception e
        (is (.contains
             (.getMessage e)
             (str "capturing socket traffic does not currently "
                  "work with async requests")))))))

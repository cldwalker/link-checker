(ns link-checker.render-test
  (:require [clojure.test :refer :all]
            [bond.james :as bond :refer [with-spy]]
            [clj-http.client :as client]
            [link-checker.fixtures :as fixtures]
            [link-checker.render :as render]))

(defn send-event-fn [& args])
(def sse-context {:request {}})

(defn verify-args-called-for [f & expected-args]
  (doseq [[expected actual] (map vector expected-args (->> f bond/calls (map :args)))]
    (if (instance? java.util.regex.Pattern (last expected))
      (is (and (= (butlast expected) (butlast actual)) (re-find (last expected) (last actual))))
      (is (= expected actual)))))

(defn stream-links
  [url options]
  (render/stream-links send-event-fn sse-context url options))

(deftest stream-links-tells-user-if-selector-is-invalid
  (with-spy [send-event-fn]
    (stream-links "http://google.com" {:selector ".no whitespace"})
    (verify-args-called-for
     send-event-fn
     [sse-context "error" "Selector is invalid. Try again."])))

;;; Yeah, this is a live request but it fails quick
(deftest stream-links-tells-user-if-url-is-invalid
  (with-spy [send-event-fn]
    (stream-links "http://2f4jf;zh5.com" {})
    (verify-args-called-for
     send-event-fn
     [sse-context "error" "Unable to fetch the given url."])))

(deftest stream-links-streams-valid-links
  (with-spy [send-event-fn]
    ;; Easiest possible links - they all return 200 for head request
    (with-redefs [client/get (constantly fixtures/successful-get)
                  client/head (constantly fixtures/successful-head)]
      (stream-links "http://google.com" {:client-id "3bj4g8j" :selector "form"}))
    (verify-args-called-for
     send-event-fn
     [sse-context "message" #"^http://google.com has 2 links"]
     [sse-context "message" #"Links remaining"]
     [sse-context "results" #"^<tr"]
     [sse-context "results" #"^<tr"]
     [sse-context "message" #"^All links are valid!"]
     [sse-context "end-message" "result?url=http://google.com&selector=form"])))
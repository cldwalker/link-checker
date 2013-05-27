(ns link-checker.http-test
  (:require [clojure.test :refer :all]
            [link-checker.http :as http]
            [clj-http.client :as client]
            [link-checker.fixtures :as fixtures]))

(defn url->links [url options]
  (with-redefs [client/get (constantly fixtures/successful-get)]
    (http/url->links url options)))

(deftest url->links-test
  (testing "with a valid url returns correct links"
    (is (= 18 (count (url->links "http://google.com" {})))))
  (testing "with a valid url expands relative link"
    (is (some #{"http://google.com/intl/en/ads/"} (url->links "http://google.com" {}))))
  (testing "with a valid url and selector returns correct links"
    (is (= 2 (count (url->links "http://google.com" {:selector "form"}))))))
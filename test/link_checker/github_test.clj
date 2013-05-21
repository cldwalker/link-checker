(ns link-checker.github-test
  (:require [clojure.test :refer :all]
            [link-checker.service :as service]
            [bond.james :as bond :refer [with-spy]]
            [tentacles.repos :as repos]
            [tentacles.issues :as issues]
            [link-checker.fixtures :as fixtures]
            [link-checker.test-helper :as test-helper]
            [link-checker.github :as github]))

(defn send-event-fn [& args])
(def sse-context {:request {}})

(defn stream-repositories
  []
  ;; we don't want to memoize during tests
  (with-redefs [github/memoized-fetch-repo-info github/fetch-repo-info
                github/memoized-fetch-authored-repos-and-active-forks github/fetch-authored-repos-and-active-forks]
    (test-helper/disallow-web-requests!
     (github/stream-repositories send-event-fn sse-context "defunkt"))))

(defn verify-args-called-for [f & expected-args]
  (doseq [[expected actual] (map vector expected-args (->> f bond/calls (map :args)))]
    (if (instance? java.util.regex.Pattern (last expected))
      (is (and (= (butlast expected) (butlast actual)) (re-find (last expected) (last actual))))
      (is (= expected actual)))))

(deftest stream-repositories-receives-403-from-github
  []
  (with-redefs [repos/user-repos (constantly fixtures/response-403)
                github/gh-auth (constantly {})]
    (with-spy [send-event-fn]
      (stream-repositories)
      (verify-args-called-for
       send-event-fn
       [sse-context "error" #"^Rate limit has been exceeded"]))))

(deftest stream-repositories-receives-404-from-github
  []
  (with-redefs [repos/user-repos (constantly fixtures/response-404)
                github/gh-auth (constantly {})]
    (with-spy [send-event-fn]
      (stream-repositories)
      (verify-args-called-for
       send-event-fn
       [sse-context "error" "Received a 404 from Github. Please try again later."]))))

(deftest stream-repositories-receives-200s-from-github
  []
  (with-redefs [repos/user-repos (constantly fixtures/response-user-repos)
                repos/specific-repo (constantly fixtures/response-specific-repo)
                issues/issues (constantly fixtures/no-issues)
                github/gh-auth (constantly {})]
    (with-spy [send-event-fn]
             (stream-repositories)
             (verify-args-called-for
              send-event-fn
              [sse-context "message" #"^defunkt has 2 repositories. Fetching data"]
              [sse-context "results" #"^<tr>.*https://github.com/defunkt/ace.*11 stars"]
              [sse-context "results" #"^<tr>.*https://github.com/defunkt/acts_as_textiled.*118 stars"]
              [sse-context "results" #"total-stats.*Total"]
              [sse-context "message" #"has 2 repositories: 1 are authored and 1 are active forks"]
              [sse-context "end-message" "defunkt"]))))

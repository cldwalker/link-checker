(ns link-checker.http
  "HTTP utility functions - mainly around fetching and parsing responses."
  (:require [io.pedestal.service.log :as log]
            [net.cgrand.enlive-html :as enlive]
            [clj-http.client :as client])
  (:import [java.net URL]))

(defn successful-status?
  "Returns true for any 2XX status"
  [status]
  (and (integer? status) (> 300 status 199)))

(def default-clj-http-options
  {:max-redirects 5
   :throw-exceptions false
   :conn-timeout 3000
   :socket-timeout 4000})

(defn client-get [url]
  (log/info :msg (format "Thread %s: GET %s" (.. Thread currentThread getId) url))
  (client/get url default-clj-http-options))

(defn client-head [url]
  (log/info :msg (format "Thread %s: HEAD %s" (.. Thread currentThread getId) url))
  (client/head url default-clj-http-options))

(defn fetch-link
  "Fetches a link by HEAD and if unsuccessful by GET. Returns response or if an unexpected
error occurs returns a map with exception in :error key."
  [url]
  (try
    (let [head (client-head url)]
      (if (> 400 (:status head) 199)
        head
        (client-get url)))
    (catch Exception err {:error err})))

(defn- body->links
  "Converts a url's body to links."
  [string options]
  (-> string
      (java.io.StringReader.)
      enlive/html-resource
      (enlive/select (if (seq (:selector options))
                       [(keyword (:selector options)) :a] [:a]))
      (as-> enlive-maps (map
                         #(get-in % [:attrs :href])
                         enlive-maps))))

(defn- invalid-link?
  [link]
  (or (contains? #{nil "" "#"} link)
      (re-find #"^(git:|javascript:|irc:|mailto:)" link)))

;;; thanks to alida's util.cl
(defn- expand-relative-links
  "Expands links in order to validate them."
  [url links]
  (let [jurl (URL. url)]
    (map #(str (URL. jurl %)) links)))

(defn url->links
  "Converts a url to a list of links. Returns nil if response is not a 200."
  [url options]
  (let [resp (try
               (client-get url)
               (catch Exception e nil))]
    (when (= 200 (:status resp))
      (expand-relative-links
       url
       (->> options
            (body->links (:body resp))
            distinct
            (remove invalid-link?))))))

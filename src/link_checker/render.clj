(ns link-checker.render
  (:require [io.pedestal.service.log :as log]
            [com.github.ragnard.hamelito.hiccup :as haml]
            [link-checker.util :refer [calc-time shorten-to]]
            [link-checker.http :refer [successful-status? url->links fetch-link
                                       default-clj-http-options]]
            [clostache.parser :as clostache]
            [clojure.repl]
            [clojure.string]))

(defn- render-haml
  [template repo-map]
  (haml/html
   (clostache/render-resource template repo-map)))

(defn- build-status
  "Returns status as an error string, a message for 3XX or a status number"
  [resp]
  (if (:error resp) (str "Request failed: " (:error resp))
      (if (> 400 (:status resp) 299)
        (format "%s - Reached redirect limit (%s) with: %s"
                (:status resp)
                (:max-redirects default-clj-http-options)
                (clojure.string/join " , " (rest (:trace-redirects resp))))
        (:status resp))))

(defn- link->result
  "Fetches a url and returns a map to render its results."
  [url]
  (log/info :msg (format "Verifying link %s ..." url))
  (let [resp (fetch-link url)
        status (build-status resp)]
    {:url url
     :shortened-url (shorten-to url 80)
     :status status
     :shortened-status (shorten-to (str status) 40)
     :tr-class (if (successful-status? (:status resp)) "success"
                   (if (:error resp) "failure" "no-success"))
     :response resp
     :thread-id (.. Thread currentThread getId)}) )

(def ^{:doc "Map of IDs to remaining links count"} link-counts (atom {}))

(defn- fetch-link-and-send-row
  "Fetches a link and sends its status via a server side event"
  [send-to url total-links client-id link]
  (let [result-map (link->result link)]
    (when (get @link-counts client-id)
      (swap! link-counts update-in [client-id] dec)
      (when (zero? (rem (get @link-counts client-id) 5))
        (send-to "message" (format "%s has %s links. Links remaining %s... <img src='/images/spinner.gif' />"
                                   url
                                   total-links
                                   (get @link-counts client-id)))))
    (send-to "results" (render-haml "public/row.haml" result-map))
    result-map))

(defn- valid-selector?
  "Only allow the most basic selector i.e. no whitespace, alphanumeric chars and div or id."
  [selector]
  (re-find #"^[\.#a-zA-Z0-9_-]+$" selector))

(defn- send-final-message
  "Sends a results event containing total row and message event summarizing checked links."
  [send-to time links]
  (send-to
   "message"
   (if (zero? (count links))
     "No links found. Check your link and selector."
     (let [invalid-links (count (remove #(successful-status? (:status %)) links))]
       (format "%s It took %ss to fetch %s links."
               (case invalid-links
                 0 "All links are valid!"
                 1 "1 link did not return a 2XX response."
                 (str invalid-links " links did not return a 2XX response."))
              time
              (count links))))))

(defn- check-links
  [send-to links url options]
  (swap! link-counts assoc (:client-id options) (count links))
  (send-to "message"
           (format "%s has %s links. Fetching data... <img src='/images/spinner.gif' />"
                   url (count links)))
  (let [start-time (System/currentTimeMillis)
        link-results (doall (pmap (partial fetch-link-and-send-row send-to url
                                           (count links) (:client-id options))
                                  links))]
    (send-final-message send-to (calc-time start-time) link-results))
  (send-to "end-message" (str "result?url=" url
                              (if (empty? (:selector options)) ""
                                (str "&selector=" (:selector options))))))

(defn- stream-links*
  "Sends 4 different sse events (message, results, end-message, error) depending on
what part of the page it's updating."
  [send-event-fn sse-context url options]
  (let [send-to (partial send-event-fn sse-context)
        selector (clojure.string/trim (str (:selector options)))]
    (if (and (seq selector) (not (valid-selector? selector)))
      (send-to "error" "Selector is invalid. Try again.")
      (if-let [links (url->links url (assoc options :selector selector))]
        (check-links send-to links url options)
        (send-to "error" "Unable to fetch the given url.")))))

(defn stream-links
  "Streams a url's verified links with a given fn and sse-context."
  [send-event-fn sse-context url options]
  (if url
    (try
      (stream-links* send-event-fn sse-context url options)
      {:status 200}
      (catch Exception exception
        (log/error :msg (str "Unexpected error: " exception))
        (clojure.repl/pst exception 25) ;; should log instead of print
        (send-event-fn sse-context "error" "An unexpected error occurred. :(")))
    (log/error :msg "No url given to verify links. Ignored.")))
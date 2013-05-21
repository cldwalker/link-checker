(ns link-checker.util
  "Misc utilities"
  (:require clj-time.core
            clj-time.format))

(defn get-in!
  "Fail fast if no truish value for get-in"
  [m ks]
  (or (get-in m ks) (throw (ex-info "No value found for nested keys in map" {:map m :keys ks}))))

(defn get!
  "Fail fast if no truish value for get"
  [m k]
  (or (get m k) (throw (ex-info "No value found for key in map" {:map m :key k}))))


(defn average
  "Takes average, rounds to two decimal places and returns as string"
  [num denom]
  (format "%.2f"
          (if (zero? denom) 0.0 (/ num (float denom)))))

(defn difference-in-hours
  "Returns the difference in hours between two timestamp sttrings, rounded to the nearest integer"
  [start end]
  (clj-time.core/in-hours
   (clj-time.core/interval (clj-time.format/parse start) (clj-time.format/parse end))))

(defn format-date
  "Formats a timestamp string to YYYY-MM-DD"
  [date]
  (when date
    (clj-time.format/unparse (clj-time.format/formatters :year-month-day) (clj-time.format/parse date))))

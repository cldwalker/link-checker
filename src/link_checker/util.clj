(ns link-checker.util
  "Misc utilities")

(defn calc-time
  "Calculates a duration to the nearest hundredth second given a ms start time."
  [start-time]
  (->> (/ (- (System/currentTimeMillis) start-time) 1000)
       float
       (format "%.2f")))

(defn shorten-to
  "Ensure a string is max-length"
  [s max-length]
  (let [s-length (count s)]
    (if (> s-length max-length)
      (str (.substring s 0 (- max-length 3)) "...")
      s)))

(defn get-in!
  "Fail fast if no truish value for get-in"
  [m ks]
  (or (get-in m ks) (throw (ex-info "No value found for nested keys in map" {:map m :keys ks}))))

(defn get!
  "Fail fast if no truish value for get"
  [m k]
  (or (get m k) (throw (ex-info "No value found for key in map" {:map m :key k}))))
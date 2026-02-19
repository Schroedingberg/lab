(ns lab-test.unit
  "Unit tests for lab - no Docker required"
  (:require [clojure.test :refer [deftest is testing run-tests]]))

;; Load lab namespace
(load-file "lab.bb")

(deftest service->compose-test
  (testing "basic service conversion"
    (let [[name spec] (lab/service->compose
                       [:myapp {:image "nginx:latest"
                                :ports ["80:80"]
                                :env {:FOO "bar"}
                                :restart "always"}])]
      (is (= "myapp" name))
      (is (= "nginx:latest" (:image spec)))
      (is (= ["80:80"] (:ports spec)))
      (is (= {"FOO" "bar"} (:environment spec)))
      (is (= "always" (:restart spec)))))

  (testing "minimal service"
    (let [[_ spec] (lab/service->compose [:bare {:image "alpine"}])]
      (is (= {:image "alpine"} spec))))

  (testing "service with volumes"
    (let [[_ spec] (lab/service->compose
                    [:db {:image "postgres"
                          :volumes ["pgdata:/var/lib/postgresql/data"]}])]
      (is (= ["pgdata:/var/lib/postgresql/data"] (:volumes spec))))))

(deftest generate-compose-test
  (testing "generates valid compose structure"
    (let [config {:services {:web {:image "nginx"}
                             :db {:image "postgres"}}}
          result (lab/generate-compose config)]
      (is (contains? result :services))
      (is (= 2 (count (:services result))))
      (is (= "nginx" (get-in result [:services "web" :image])))))

  (testing "empty services returns empty map"
    (is (= {} (lab/generate-compose {:services {}})))))

(deftest resolve-refs-test
  (testing "resolves nested refs"
    (let [config {:secrets {:token "abc123"}
                  :service {:auth [:ref [:secrets :token]]}}
          resolved (lab/resolve-refs config config)]
      (is (= "abc123" (get-in resolved [:service :auth])))))

  (testing "resolves env vars"
    (let [config {:path [:env "HOME"]}
          resolved (lab/resolve-refs config config)]
      (is (string? (:path resolved)))
      (is (not (empty? (:path resolved))))))

  (testing "resolves join"
    (let [config {:secrets {:base "http://example.com"}
                  :url [:join [:ref [:secrets :base]] "/api"]}
          resolved (lab/resolve-refs config config)]
      (is (= "http://example.com/api" (:url resolved)))))

  (testing "preserves non-ref values"
    (let [config {:foo "bar" :num 42}
          resolved (lab/resolve-refs config config)]
      (is (= "bar" (:foo resolved)))
      (is (= 42 (:num resolved))))))

(deftest format-date-test
  (testing "returns formatted date string"
    (let [result (lab/format-date)]
      (is (string? result))
      (is (re-matches #"[A-Z][a-z]+ \d+, \d{4}" result)))))

(deftest interpolate-body-test
  (testing "replaces :current-date keyword"
    (let [body {:name :current-date}
          result (lab/interpolate-body body)]
      (is (string? (:name result)))
      (is (re-matches #"[A-Z][a-z]+ \d+, \d{4}" (:name result)))))

  (testing "leaves other values unchanged"
    (let [body {:foo "bar" :num 42}
          result (lab/interpolate-body body)]
      (is (= body result)))))

(deftest config->yaml-test
  (testing "generates valid YAML"
    (let [config {:services {:test {:image "alpine" :ports ["8080:80"]}}}
          yaml (lab/config->yaml config)]
      (is (clojure.string/includes? yaml "alpine"))
      (is (clojure.string/includes? yaml "8080:80"))
      (is (clojure.string/includes? yaml "services:")))))

;; Run tests when executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests)]
    (System/exit (if (and (zero? fail) (zero? error)) 0 1))))

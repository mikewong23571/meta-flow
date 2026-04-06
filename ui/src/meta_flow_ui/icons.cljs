(ns meta-flow-ui.icons)

(defn refresh []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :strokeWidth "2"
         :strokeLinecap "round"
         :strokeLinejoin "round"
         :className "icon"}
   [:path {:d "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"}]
   [:path {:d "M21 3v5h-5"}]
   [:path {:d "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"}]
   [:path {:d "M8 16H3v5"}]])

(defn close []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :strokeWidth "2"
         :strokeLinecap "round"
         :strokeLinejoin "round"
         :className "icon"}
   [:path {:d "M18 6 6 18"}]
   [:path {:d "m6 6 12 12"}]])

(defn home []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :strokeWidth "2"
         :strokeLinecap "round"
         :strokeLinejoin "round"
         :className "icon"}
   [:path {:d "m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"}]
   [:polyline {:points "9 22 9 12 15 12 15 22"}]])

(defn plus []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :strokeWidth "2"
         :strokeLinecap "round"
         :strokeLinejoin "round"
         :className "icon"}
   [:path {:d "M5 12h14"}]
   [:path {:d "M12 5v14"}]])

(defn arrow-right []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :strokeWidth "2"
         :strokeLinecap "round"
         :strokeLinejoin "round"
         :className "icon"}
   [:path {:d "M5 12h14"}]
   [:path {:d "m12 5 7 7-7 7"}]])

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.loader
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [rumext.v2 :as mf]))

(mf/defc loader*
  {::mf/props :obj}
  [{:keys [class width height title] :rest props}]
  (let [class (dm/str (or class "") " " (stl/css :loader))
        both-provided (and width height)
        neither-provided (and (nil? width) (nil? height))
        props (mf/spread-props props {:viewBox "0 0 677.34762 182.15429"
                                      :role "status"
                                      :width (or width "100px")
                                      :height (or height "27px")
                                      :class class})]
    (assert (or both-provided neither-provided)
            (dm/str "Invalid props: both 'width' and 'height' must be provided or neither. "
                    "Received width: " width ", height: " height))
    ;; TODO: Add a translated label insted of the title prop.    
    (assert title
            (dm/str "You must provide an accesible name for the component"))
    [:> "svg" props
     [:title title]
     [:g
      [:path {:d
              "M128.273 0l-3.9 2.77L0 91.078l128.273 91.076 549.075-.006V.008L128.273 0zm20.852 30l498.223.006V152.15l-498.223.007V30zm-25 9.74v102.678l-49.033-34.813-.578-32.64 49.61-35.225z"}]
      [:path {:class (stl/css :loader-line)
              :d
              "M134.482 157.147v25l518.57.008.002-25-518.572-.008z"}]]]))

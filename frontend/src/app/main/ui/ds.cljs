;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.main.ui.ds.foundations.assets.raw-svg :refer [raw-svg* raw-svg-list]]
   [app.main.ui.ds.foundations.typography :refer [typography-list]]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.ds.storybook :as sb]))

(def default
  "A export used for storybook"
  #js {:Heading heading*
       :Icon icon*
       :Loader loader*
       :RawSvg raw-svg*
       :Text text*
       ;; meta / misc
       :meta #js {:icons icon-list
                  :svgs raw-svg-list
                  :typography (clj->js typography-list)}
       :storybook #js {:StoryGrid sb/story-grid*
                       :StoryGridCell sb/story-grid-cell*
                       :StoryGridRow sb/story-grid-row*
                       :StoryHeader sb/story-header*}})

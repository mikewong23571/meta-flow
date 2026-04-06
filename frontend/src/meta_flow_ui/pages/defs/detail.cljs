(ns meta-flow-ui.pages.defs.detail
  (:require [meta-flow-ui.pages.defs.detail.runtime-profile :as runtime-profile]
            [meta-flow-ui.pages.defs.detail.task-type :as task-type]))

(def task-type-detail-page
  task-type/task-type-detail-page)

(def runtime-profile-detail-page
  runtime-profile/runtime-profile-detail-page)

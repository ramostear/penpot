;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.management
  "A collection of RPC methods for manage the files, projects and team organization."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.migrations :as pmg]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as feat.fdata]
   [app.http.sse :as sse]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.binfile :as binfile]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.projects :as proj]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.setup :as-alias setup]
   [app.setup.templates :as tmpl]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.walk :as walk]
   [promesa.core :as p]
   [promesa.exec :as px]))

;; --- COMMAND: Duplicate File

(declare duplicate-file)

(def ^:private
  schema:duplicate-file
  (sm/define
    [:map {:title "duplicate-file"}
     [:file-id ::sm/uuid]
     [:name {:optional true} :string]]))

(sv/defmethod ::duplicate-file
  "Duplicate a single file in the same team."
  {::doc/added "1.16"
   ::webhooks/event? true
   ::sm/params schema:duplicate-file}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg duplicate-file (assoc params :profile-id profile-id)))

(defn- remap-id
  [item index key]
  (cond-> item
    (contains? item key)
    (assoc key (get index (get item key) (get item key)))))

(defn- process-file
  [cfg index {:keys [id] :as file}]
  (letfn [(process-form [form]
            (cond-> form
              ;; Relink library items
              (and (map? form)
                   (uuid? (:component-file form)))
              (update :component-file #(get index % %))

              (and (map? form)
                   (uuid? (:fill-color-ref-file form)))
              (update :fill-color-ref-file #(get index % %))

              (and (map? form)
                   (uuid? (:stroke-color-ref-file form)))
              (update :stroke-color-ref-file #(get index % %))

              (and (map? form)
                   (uuid? (:typography-ref-file form)))
              (update :typography-ref-file #(get index % %))

              ;; Relink Image Shapes
              (and (map? form)
                   (map? (:metadata form))
                   (= :image (:type form)))
              (update-in [:metadata :id] #(get index % %))))

          ;; A function responsible to analyze all file data and
          ;; replace the old :component-file reference with the new
          ;; ones, using the provided file-index
          (relink-shapes [data]
            (walk/postwalk process-form data))

          ;; A function responsible of process the :media attr of file
          ;; data and remap the old ids with the new ones.
          (relink-media [media]
            (reduce-kv (fn [res k v]
                         (let [id (get index k)]
                           (if (uuid? id)
                             (-> res
                                 (assoc id (assoc v :id id))
                                 (dissoc k))
                             res)))
                       media
                       media))

          (update-fdata [fdata new-id]
            (-> fdata
                (assoc :id new-id)
                (pmg/migrate-data)
                (update :pages-index relink-shapes)
                (update :components relink-shapes)
                (update :media relink-media)
                (d/without-nils)
                (feat.fdata/process-pointers pmap/clone)))]

    (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg id)
              pmap/*tracked* (pmap/create-tracked)
              cfeat/*new*    (atom #{})]
      (let [new-id (get index id)
            file   (-> file
                       (assoc :id new-id)
                       (update :data update-fdata new-id)
                       (update :features into (deref cfeat/*new*))
                       (update :features cfeat/migrate-legacy-features))]
        (feat.fdata/persist-pointers! cfg new-id)
        file))))

(def sql:get-used-libraries
  "select flr.*
     from file_library_rel as flr
    inner join file as l on (flr.library_file_id = l.id)
    where flr.file_id = ?
      and l.deleted_at is null")

(def sql:get-used-media-objects
  "select fmo.*
     from file_media_object as fmo
    inner join storage_object as so on (fmo.media_id = so.id)
    where fmo.file_id = ?
      and so.deleted_at is null")

(defn duplicate-file*
  [{:keys [::db/conn] :as cfg} {:keys [profile-id file index project-id name flibs fmeds]} {:keys [reset-shared-flag]}]
  (let [flibs    (or flibs (db/exec! conn [sql:get-used-libraries (:id file)]))
        fmeds    (or fmeds (db/exec! conn [sql:get-used-media-objects (:id file)]))

        ;; memo uniform creation/modification date
        now      (dt/now)
        ignore   (dt/plus now (dt/duration {:seconds 5}))

        ;; add to the index all file media objects.
        index    (reduce #(assoc %1 (:id %2) (uuid/next)) index fmeds)

        flibs-xf (comp
                  (map #(remap-id % index :file-id))
                  (map #(remap-id % index :library-file-id))
                  (map #(assoc % :synced-at now))
                  (map #(assoc % :created-at now)))

        ;; remap all file-library-rel row
        flibs    (sequence flibs-xf flibs)

        fmeds-xf (comp
                  (map #(assoc % :id (get index (:id %))))
                  (map #(assoc % :created-at now))
                  (map #(remap-id % index :file-id)))

        ;; remap all file-media-object rows
        fmeds   (sequence fmeds-xf fmeds)

        file    (cond-> file
                  (some? project-id)
                  (assoc :project-id project-id)

                  (some? name)
                  (assoc :name name)

                  (true? reset-shared-flag)
                  (assoc :is-shared false))

        file    (-> file
                    (assoc :created-at now)
                    (assoc :modified-at now)
                    (assoc :ignore-sync-until ignore))

        file    (process-file cfg index file)]

    (db/insert! conn :file
                (-> file
                    (update :features #(db/create-array conn "text" %))
                    (update :data blob/encode)))

    (db/insert! conn :file-profile-rel
                {:file-id (:id file)
                 :profile-id profile-id
                 :is-owner true
                 :is-admin true
                 :can-edit true})

    (doseq [params flibs]
      (db/insert! conn :file-library-rel params))

    (doseq [params fmeds]
      (db/insert! conn :file-media-object params))

    file))

(defn duplicate-file
  [{:keys [::db/conn] :as cfg} {:keys [profile-id file-id] :as params}]
  (let [;; We don't touch the original file on duplication
        file   (files/get-file cfg file-id :migrate? false)
        index  {file-id (uuid/next)}
        params (assoc params :index index :file file)]
    (proj/check-edition-permissions! conn profile-id (:project-id file))
    (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])
    (duplicate-file* cfg params {:reset-shared-flag true})))

;; --- COMMAND: Duplicate Project

(declare duplicate-project)

(def ^:private
  schema:duplicate-project
  (sm/define
    [:map {:title "duplicate-project"}
     [:project-id ::sm/uuid]
     [:name {:optional true} :string]]))

(sv/defmethod ::duplicate-project
  "Duplicate an entire project with all the files"
  {::doc/added "1.16"
   ::webhooks/event? true
   ::sm/params schema:duplicate-project}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg duplicate-project (assoc params :profile-id profile-id)))

(defn duplicate-project
  [{:keys [::db/conn] :as cfg} {:keys [profile-id project-id name] :as params}]

  ;; Defer all constraints
  (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])

  (let [project (-> (db/get-by-id conn :project project-id)
                    (assoc :is-pinned false))

        files   (db/query conn :file
                          {:project-id (:id project)
                           :deleted-at nil}
                          {:columns [:id]})

        project (cond-> project
                  (string? name)
                  (assoc :name name)

                  :always
                  (assoc :id (uuid/next)))]

    ;; Check if the source team-id allow creating new project for current user
    (teams/check-edition-permissions! conn profile-id (:team-id project))

    ;; create the duplicated project and assign the current profile as
    ;; a project owner
    (teams/create-project conn project)
    (teams/create-project-role conn profile-id (:id project) :owner)

    ;; duplicate all files
    (let [index  (reduce #(assoc %1 (:id %2) (uuid/next)) {} files)
          params (-> params
                     (dissoc :name)
                     (assoc :project-id (:id project))
                     (assoc :index index))]
      (doseq [{:keys [id]} files]
        (let [file   (files/get-file cfg id :migrate? false)
              params (assoc params :file file)
              opts   {:reset-shared-flag false}]
          (duplicate-file* cfg params opts))))

    ;; return the created project
    project))

;; --- COMMAND: Move file

(def sql:get-files
  "select id, features, project_id from file where id = ANY(?)")

(def sql:move-files
  "update file set project_id = ? where id = ANY(?)")

(def sql:delete-broken-relations
  "with broken as (
     (select * from file_library_rel as flr
       inner join file as f on (flr.file_id = f.id)
       inner join project as p on (f.project_id = p.id)
       inner join file as lf on (flr.library_file_id = lf.id)
       inner join project as lp on (lf.project_id = lp.id)
       where p.id = ANY(?)
         and lp.team_id != p.team_id)
   )
   delete from file_library_rel as rel
    using broken as br
    where rel.file_id = br.file_id
      and rel.library_file_id = br.library_file_id")

(defn move-files
  [{:keys [::db/conn] :as cfg} {:keys [profile-id ids project-id] :as params}]

  (let [fids    (db/create-array conn "uuid" ids)
        files   (->> (db/exec! conn [sql:get-files fids])
                     (map files/decode-row))
        source  (into #{} (map :project-id) files)
        pids    (->> (conj source project-id)
                     (db/create-array conn "uuid"))]

    (when (contains? source project-id)
      (ex/raise :type :validation
                :code :cant-move-to-same-project
                :hint "Unable to move a file to the same project"))

    ;; Check if we have permissions on the destination project
    (proj/check-edition-permissions! conn profile-id project-id)

    ;; Check if we have permissions on all source projects
    (doseq [project-id source]
      (proj/check-edition-permissions! conn profile-id project-id))

    ;; Check the team compatibility
    (let [orig-team (teams/get-team conn :profile-id profile-id :project-id (first source))
          dest-team (teams/get-team conn :profile-id profile-id :project-id project-id)]
      (cfeat/check-teams-compatibility! orig-team dest-team)

      ;; Check if all pending to move files are compaib
      (let [features (cfeat/get-team-enabled-features cf/flags dest-team)]
        (doseq [file files]
          (cfeat/check-file-features! features (:features file)))))

    ;; move all files to the project
    (db/exec-one! conn [sql:move-files project-id fids])

    ;; delete possible broken relations on moved files
    (db/exec-one! conn [sql:delete-broken-relations pids])

    ;; Update the modification date of the all affected projects
    ;; ensuring that the destination project is the most recent one.
    (doseq [project-id (into (list project-id) source)]

      ;; NOTE: as this is executed on virtual thread, sleeping does
      ;; not causes major issues, and allows an easy way to set a
      ;; trully different modification date to each file.
      (px/sleep 10)
      (db/update! conn :project
                  {:modified-at (dt/now)}
                  {:id project-id}))

    nil))

(def ^:private
  schema:move-files
  (sm/define
    [:map {:title "move-files"}
     [:ids ::sm/set-of-uuid]
     [:project-id ::sm/uuid]]))

(sv/defmethod ::move-files
  "Move a set of files from one project to other."
  {::doc/added "1.16"
   ::webhooks/event? true
   ::sm/params schema:move-files}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg #(move-files % (assoc params :profile-id profile-id))))

;; --- COMMAND: Move project

(defn move-project
  [{:keys [::db/conn] :as cfg} {:keys [profile-id team-id project-id] :as params}]
  (let [project (db/get-by-id conn :project project-id {:columns [:id :team-id]})
        pids    (->> (db/query conn :project {:team-id (:team-id project)} {:columns [:id]})
                     (map :id)
                     (db/create-array conn "uuid"))]

    (when (= team-id (:team-id project))
      (ex/raise :type :validation
                :code :cant-move-to-same-team
                :hint "Unable to move a project to same team"))

    (teams/check-edition-permissions! conn profile-id (:team-id project))
    (teams/check-edition-permissions! conn profile-id team-id)

    ;; Check the teams compatibility
    (let [orig-team (teams/get-team conn :profile-id profile-id :team-id (:team-id project))
          dest-team (teams/get-team conn :profile-id profile-id :team-id team-id)]
      (cfeat/check-teams-compatibility! orig-team dest-team)

      ;; Check if all pending to move files are compaib
      (let [features (cfeat/get-team-enabled-features cf/flags dest-team)]
        (doseq [file (->> (db/query conn :file
                                    {:project-id project-id}
                                    {:columns [:features]})
                          (map files/decode-row))]
          (cfeat/check-file-features! features (:features file)))))

    ;; move project to the destination team
    (db/update! conn :project
                {:team-id team-id}
                {:id project-id})

    ;; delete possible broken relations on moved files
    (db/exec-one! conn [sql:delete-broken-relations pids])

    nil))

(def ^:private
  schema:move-project
  (sm/define
    [:map {:title "move-project"}
     [:team-id ::sm/uuid]
     [:project-id ::sm/uuid]]))

(sv/defmethod ::move-project
  "Move projects between teams"
  {::doc/added "1.16"
   ::webhooks/event? true
   ::sm/params schema:move-project}
  [cfg {:keys [::rpc/profile-id] :as params}]
  (db/tx-run! cfg #(move-project % (assoc params :profile-id profile-id))))

;; --- COMMAND: Clone Template

(def ^:private
  schema:clone-template
  (sm/define
    [:map {:title "clone-template"}
     [:project-id ::sm/uuid]
     [:template-id ::sm/word-string]]))

(declare ^:private clone-template)

(sv/defmethod ::clone-template
  "Clone into the specified project the template by its id."
  {::doc/added "1.16"
   ::sse/stream? true
   ::webhooks/event? true
   ::sm/params schema:clone-template}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id project-id template-id] :as params}]
  (let [project   (db/get-by-id pool :project project-id {:columns [:id :team-id]})
        _         (teams/check-edition-permissions! pool profile-id (:team-id project))
        template  (tmpl/get-template-stream cfg template-id)
        params    (-> cfg
                      (assoc ::binfile/input template)
                      (assoc ::binfile/project-id (:id project))
                      (assoc ::binfile/profile-id profile-id)
                      (assoc ::binfile/ignore-index-errors? true)
                      (assoc ::binfile/migrate? true))]

    (when-not template
      (ex/raise :type :not-found
                :code :template-not-found
                :hint "template not found"))

    (sse/response #(clone-template params))))

(defn- clone-template
  [{:keys [::wrk/executor ::binfile/project-id] :as params}]
  (db/tx-run! params
              (fn [{:keys [::db/conn] :as params}]
                ;; NOTE: the importation process performs some operations that
                ;; are not very friendly with virtual threads, and for avoid
                ;; unexpected blocking of other concurrent operations we
                ;; dispatch that operation to a dedicated executor.
                (let [result (p/thread-call executor (partial binfile/import! params))]
                  (db/update! conn :project
                              {:modified-at (dt/now)}
                              {:id project-id})

                  (deref result)))))

;; --- COMMAND: Get list of builtin templates

(sv/defmethod ::get-builtin-templates
  {::doc/added "1.19"}
  [cfg _params]
  (mapv #(select-keys % [:id :name]) (::setup/templates cfg)))

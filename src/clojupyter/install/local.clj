(ns clojupyter.install.local
  ;; This namespace provides functions to install Clojupyter on the local machine.  The Clojupyter
  ;; kernel can be installed for the current user only or available to all users on the machine.

  ;; Functions whose name begins with 's*' return a single-argument function accepting and returning
  ;; a state map.
  (:require
   [clojure.java.io			:as io]
   [clojure.pprint			:as pprint	:refer :all]
   [clojure.set				:as set]
   [clojure.spec.alpha			:as s]
   [clojure.spec.test.alpha				:refer [instrument]]
   [clojure.string			:as str]
   [io.simplect.compose					:refer [call-if redefn def- sdefn sdefn-
                                                                >->> >>-> π Π γ Γ λ]]
   [me.raynes.fs			:as fs]
   ,,
   [clojupyter.cmdline.api		:as cmdline]
   [clojupyter.install.filemap		:as fm]
   [clojupyter.install.local-actions	:as local!]
   [clojupyter.install.local-specs	:as lsp]
   [clojupyter.install.plan				:refer :all]
   [clojupyter.kernel.version		:as version]
   [clojupyter.util			:as u]
   [clojupyter.util-actions		:as u!])
  (:gen-class))

(def DEFAULT-LOGLEVELS #{:error :warn :info})

(defn- pprint-table-to-string
  [v]
  (with-out-str (pprint/print-table v)))

(defn- format-report
  [user-homedir matching-kernels ident-colname dir-colname]
  (->> matching-kernels
       (sort-by :kernel/ident)
       (map (Γ (Π select-keys [:kernel/ident :kernel/dir])
               (Π set/rename-keys {:kernel/ident ident-colname, :kernel/dir dir-colname})))
       (u/files-as-strings user-homedir)
       pprint-table-to-string
       str/split-lines
       (drop-while (π = ""))))

(defn- destination-directory
  [ident user-destdir user-loc host-kernel-dir user-kernel-dir]
  (let [sanitize (u/sanitize-string lsp/IDENT-CHAR-REGEX)
        ident (sanitize ident)]
    (-> (or user-destdir
            (-> (case user-loc
                  :loc/host host-kernel-dir
                  :loc/user user-kernel-dir
                  (u!/throw-info (Exception. (str "destdir: Unexpected user-loc '" user-loc "'."))
                    {:ident ident, :user-loc user-loc}))
                (str "/" ident)))
        io/file)))

(defn- source-jarfiles
  [user-jarfiles env-jarfiles]
  (if (-> user-jarfiles count pos?)
    user-jarfiles
    env-jarfiles))

(sdefn install-spec (s/cat :user-opts :local/user-opts :install-env :local/install-env)
  "Given a representation of the choices provided by the user (`user-opts`) and values found in the
  environment (`install-env`), returns a map specifying how Clojupyter is to be installed.

  `user-opts` must conform to the spec `:local/user-opts` and `install-env` to
  `:local/install-env`.

  The returned value conforms to the spec `:local/install-spec`."
  [user-opts install-env]
  (letfn [(U [kw] (get user-opts kw))
          (E [kw] (get install-env kw))
          (OR ([kw default] (or (U kw) (E kw) default)), ([kw] (OR kw nil)))]
    (let [ident (or (U :local/ident) (E :local/default-ident))
          destdir (destination-directory
                   ident
                   (U :local/destdir)
                   (U :local/loc)
                   (E :local/host-kernel-dir)
                   (E :local/user-kernel-dir))
          src-jars (source-jarfiles (U :local/source-jarfiles), (E :local/jarfiles))
          file-copyspec (into {} (map vector src-jars (repeat lsp/DEFAULT-TARGET-JARNAME)))
          resource-copyspec {lsp/LOGO-ASSET (-> lsp/LOGO-ASSET io/file .getName)}
          convert-exe (E :local/convert-exe)
          res (merge {:local/allow-deletions?		(U :local/allow-deletions?)
                      :local/allow-destdir?		(U :local/allow-destdir?)
                      :local/destdir			destdir
                      :local/filemap			(fm/filemap (E :local/filemap) (U :local/filemap))
                      :local/file-copyspec		file-copyspec
                      :local/generate-kernel-json?	(U :local/generate-kernel-json?)
                      :local/icon-bot			(U :local/icon-bot)
                      :local/icon-top			(U :local/icon-top)
                      :local/ident			ident
                      :local/installed-kernels		(E :local/installed-kernels)
                      :local/logo-resource		(E :local/logo-resource)
                      :local/resource-copyspec		resource-copyspec
                      :local/resource-map		(E :local/resource-map)
                      :local/customize-icons?		(U :local/customize-icons?)
                      :local/source-jarfiles		src-jars
                      :version/version-map		(E :version/version-map)}
                     (when convert-exe
                       {:local/convert-exe convert-exe}))]
      (when-not (s/valid? :local/install-spec res)
        (u!/throw-info "Bad install-spec"
          {:user-opts user-opts, :install-env install-env,
           :result res, :explain-str (s/explain-str :local/install-spec res)}))
      res)))

;;; ----------------------------------------------------------------------------------------------------
;;; STATE-PASSING FUNCTIONS
;;; ----------------------------------------------------------------------------------------------------

(sdefn s*generate-install-effects (s/cat :install-spec :local/install-spec)
  "Adds a sequence of effects to the threaded state using `s*action-append`.  For each error identified
  error condition `s*log-error` is called to provide details.  If no errors are found the added
  effects will, if invoked, install Clojupyter according to `install-spec`."
  [install-spec]
  (let [#:local{:keys [allow-deletions? allow-destdir? convert-exe destdir filemap file-copyspec
                       icon-bot icon-top ident installed-kernels logo-resource resource-map resource-copyspec
                       customize-icons? generate-kernel-json? source-jarfiles]} install-spec
        installed-idents (->> installed-kernels keys (into #{}))]
    (assert (fm/filemap? filemap))
    (letfn [(get-resource [nm] (get resource-map nm))
            (destdir-file [nm] (io/file (str destdir "/" (-> nm io/file .getName))))
            (resource-destfile [rnm] (->> rnm (get resource-copyspec) destdir-file))
            (msg [m] (assoc m :install-spec install-spec))]
      (Γ
       ;; CHECK PRECONDITIONS
       (s*when (-> ident count zero?)
         (s*log-error {:message (str "Error: zero length kernel identifier not permitted.")
                       :type :bad-ident}))
       (s*when (contains? installed-idents ident)
         (s*log-error {:message (str "Error: A Clojupyter kernel named '" ident "' is already installed.")
                       :type :ident-already-installed}))
       (s*when (and (not allow-destdir?) (fm/exists filemap destdir))
         (s*log-error (msg {:message (str "Error: '" destdir "' already exists.")
                            :type :destdir-exists})))
       (s*when (and allow-destdir? (fm/exists filemap destdir) (not (fm/dir filemap destdir)))
         (s*log-error (msg {:message (str "Destination directory '" destdir "' not a directory.")
                            :type :destdir-exists-but-not-directory})))
       (case (count source-jarfiles)
         0 (s*log-error (msg {:message (str "Error: No source jarfile provided.")
                              :type :no-source-jarfile}))
         1 s*ok
         (s*log-error (msg {:message (str "Multiple source jarfiles available: " source-jarfiles ".")
                            :type :multiple-source-jarfiles})))

       ;; CREATE DESTDIR
       (s*action-append [`fs/mkdirs destdir])

       ;; COPY RESOURCES
       (apply Γ (for [[rname destname] resource-copyspec]
                  (if-let [r (get-resource rname)]
                    (s*action-append [`local!/copy-resource-to-file! rname (destdir-file destname)])
                    (s*log-error (msg {:message (str "Error: Cannot find resource rname (" logo-resource ").")
                                       :type :missing-resource})))))

       ;; COPY FILES
       (apply Γ (for [[fname destname] file-copyspec]
                  (if-let [f (fm/file filemap fname)]
                    (s*action-append [`io/copy f (destdir-file destname)])
                    (s*log-error (msg {:message (str "Error: File '" fname "' not found.")
                                       :type :copyfile-not-found})))))

       ;; GENERATE KERNEL.JSON
       (s*when generate-kernel-json?
         (s*action-append [`local!/generate-kernel-json-file! destdir ident]))

       ;; CUSTOMIZE ICONS
       (s*when customize-icons?
         (if-let [conv (fm/file filemap convert-exe)]
           (s*action-append [`local!/customize-icon-file! conv, {:south icon-bot, :north icon-top},
                             (resource-destfile lsp/LOGO-ASSET)])
           (s*log-error (msg {:message (str "Error: Imagemagick 'convert' executable not found.")
                              :type :convert-exe-not-found}))))))))

(defn s*report-install
  "Returns a function which, given a state, uses the cmdline api to update state with user output
  regarding the installation result."
  [env install-spec log-levels]
  (s*bind-state S
    (let [#:local{:keys [user-homedir]} env
          #:local{:keys [destdir ident source-jarfiles]} install-spec
          log (get-log S)
          log-levels (or log-levels DEFAULT-LOGLEVELS)
          success? (execute-success? S)]
      (assert user-homedir)
      (Γ (cmdline/set-header "Install local")
         (cmdline/outputs (u/log-messages log-levels log))
         (cmdline/set-exit-code (if success? 0 1))
         (s*when success?
           (cmdline/outputs [(str "Installed jar:\t" (->> source-jarfiles first (u/tildeize-filename user-homedir)))
                             (str "Install directory:\t" (u/tildeize-filename user-homedir destdir))
                             (str "Kernel identifier:\t"  ident)]))
         (cmdline/outputs ["" (if success?
                                "Installation successful."
                                "Installation failed.")])))))

(def s*report-remove
  "Uses the cmdline api to update state with user output regarding the removal result."
  (s*bind-state S
    (let [log (get-log S)
          success? (execute-success? S)]
      (Γ (cmdline/set-header "Remove installs")
         (cmdline/outputs (u/log-messages log))
         (cmdline/set-exit-code (if success? 0 1))
         (cmdline/outputs [""
                           (str "Status: "
                            (cond
                              (halted? S)	"Execution skipped."
                              success?		"Removals successfully completed."
                              :else		"Error(s) occurred during removal (log messages above)."))])))))

;;; ----------------------------------------------------------------------------------------------------
;;; USED FROM CLOJUPYTER.CMDLINE
;;; ----------------------------------------------------------------------------------------------------

(sdefn s*install (s/cat :opts map? :user-opts :local/user-opts :install-env :local/install-env)
  "Return a function which, given an initial state, returns a new state with the installation
  results."
  [{:keys [skip-execute? skip-report? log-levels] :as opts} user-opts install-env]
  (let [log-levels (or log-levels #{:info :warn :error})
        spec (install-spec user-opts install-env)]
    (Γ (cmdline/set-header "Install Clojupyter local")
       (s*when-not skip-execute? s*set-do-execute)
       (s*generate-install-effects spec)
       s*execute
       (s*when-not skip-report?
         (s*report-install install-env spec log-levels)))))

(sdefn s*list-installs-matching (s/cat :env :local/install-env :regex-string string?)
  "Returns a function which, given a state, updates the state with information about Clojuputer kernels
  with kernels matching `regex-string`."
  [env regex-string]
  (let [{:keys [:local/installed-kernels :local/user-homedir]} env
        installed-idents (->> installed-kernels keys (into #{}))]
    (Γ (cmdline/set-header (str "Clojupyter kernels matching the regular expression '" regex-string "'."))
       (if-let [patt (u/re-pattern+ regex-string)]
         (let [matching-kernels (->> installed-kernels
                                     vals
                                     (filter (Γ :kernel/ident str (π re-find patt))))
               matches (filter (π re-find patt) installed-idents)
               match? (-> matches count pos?)
               result (if match?
                        (format-report user-homedir matching-kernels "IDENT" "DIR")
                        [(str "No kernels match '" regex-string "'.")])]
           (Γ (cmdline/outputs result)
              (cmdline/set-result {:matching-kernels matching-kernels})
              (cmdline/set-exit-code (if match? 0 1))))
         (Γ (cmdline/output (str "Not a legal regular expression: " regex-string))
            (cmdline/set-result {:bad-regex-string regex-string})
            (cmdline/set-exit-code 0))))))

(sdefn s*generate-remove-action (s/cat :regex-string string?, :env :local/remove-env)
  [regex-string install-env]
  (if-let [regex (u/re-pattern+ regex-string)]
    (let [{:keys [:local/kernelmap]} install-env
          matchmap (->> kernelmap
                        (filter (Γ second
                                   :display_name
                                   (fnil u/display-name->ident "")
                                   (π re-find regex)))
                        (into {}))]
      (if (-> matchmap count pos?)
        (apply Γ (for [[kernel-json-file kernel-json-info] matchmap]
                   (let [kerneldir (.getParentFile kernel-json-file)]
                     (Γ (s*log-debug {:op :remove-install
                                      :kerneldir kerneldir
                                      :kernel-json kernel-json-file
                                      :info kernel-json-info})
                        (s*action-append [`fs/delete-dir kerneldir])
                        (s*log-info {:message (str "Step: Delete " kerneldir)})))))
        (s*log-error {:message (str "No kernels matching #\"" regex-string "\" found.")
                      :type :no-matching-kernels-found
                      :matchmap matchmap})))
    (Γ (s*log-error {:message (str "Not a legal regular expression: " regex-string)
                     :type :bad-regex-string
                     :regex-string regex-string}))))

(instrument `install-spec)
(instrument `s*generate-remove-action)
(instrument `s*install)
(instrument `s*list-installs-matching)

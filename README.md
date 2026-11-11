# Red Hat Hardened Images 除錯實作 Lab

在 OpenShift 上練習替 **沒有除錯工具的 hardened image** 找問題。

## 先認識目標映像檔

`registry.access.redhat.com/hi/openjdk:latest`（JDK 25，作業系統為 Hummingbird OS）

| 有 | 沒有 |
|---|---|
| bash、coreutils（ls、cat、grep、sed…）、getent | `dnf` / `microdnf` / `rpm`（不能臨時裝工具） |
| JDK 工具：`jcmd`、`jstack`、`jfr`、`jmap` | `ps`、`ss`、`ip`、`curl`、`strace`、`tcpdump`、`tar`、`hostname` |

- 沒有 `tar` → **`oc cp` 對 app 容器無效**。
- 沒有 `ps`、`ss` → 只靠 `oc exec` 很難看出行程和網路狀態。
- 所有 lab Pod 都用 `restricted-v2` SCC：隨機 UID、**不能加 capability**。這跟正式環境一般應用的權限一樣。

> 核心觀念：工具不需要裝在目標映像檔裡。只要讓帶工具的容器**加入目標的 namespace**（PID、網路），
> 再透過 `/proc/<pid>/root` 讀目標的檔案系統就行。

## 怎麼選工具

不需要每種工具都用一遍，**依情境挑最輕、最直接的那一個**：

```
問題 Pod 的行程還活著嗎？
│
├─ 沒有（CrashLoopBackOff、一啟動就結束）
│    1. oc describe pod / oc logs --previous         ← 很多時候這樣就找到答案了
│    2. oc debug deploy/<name> --keep-annotations      ← 起一個不會 crash 的副本，慢慢看、先試修正
│
└─ 活著
     ├─ 映像檔裡就有需要的工具（例如 jcmd、cat、bash）
     │    → oc exec                                     ← 最簡單，不用額外的東西
     │
     ├─ 需要映像檔裡沒有的工具（ss、curl、tar、strace），但不需要額外權限
     │    → kubectl debug <pod> --target=app --image=$TOOLS   ← ephemeral container，共用 PID 和網路
     │
     └─ 需要 SCC 不允許的權限（tcpdump 要 NET_ADMIN、kernel log、root）
          → oc debug node/<node> + nsenter             ← 需要 cluster-admin，最後才用
```

| 工具 | 什麼時候用 | 代價 |
|---|---|---|
| `oc exec` | 映像檔裡就有需要的工具 | 沒有 |
| `kubectl debug --target` | 行程活著，缺工具 | 會改到原本的 Pod spec，ephemeral container 移除不掉 |
| `oc debug deploy/x` | 行程活不久，要一個一樣的環境 | 看不到原本的行程；**預設會換掉 SCC**，要加 `--keep-annotations` |
| `oc debug node/x` | 需要特權 | 需要 cluster-admin，等於擁有整台節點的權限 |

兩種 debug 的詳細差異見 [SOLUTIONS.md 最後一節](SOLUTIONS.md#oc-debug-vs-kubectl-debug)。

## 工具映像檔

| 用途 | 映像檔 |
|---|---|
| 通用工具箱（ps、ss、ip、curl、strace、tcpdump、nsenter、tar、nc） | `registry.redhat.io/rhel9/support-tools:latest` |
| 跨容器操作 JVM（jcmd 版本與目標一致） | `registry.access.redhat.com/hi/openjdk:latest` |

每開一個新 terminal 都先貼這段：
```bash
cd hi-debug   # 這個 repo 的目錄
export NS=hi-debug-lab
export TOOLS=registry.redhat.io/rhel9/support-tools:latest
export JDK=registry.access.redhat.com/hi/openjdk:latest
oc project $NS

# kubectl 1.27 的 debug 不接受 deploy/xxx，要給 Pod 名稱
pod() { oc get pod -l app=$1 -o name | head -1 | cut -d/ -f2; }
```

## 環境操作

```bash
./scripts/setup.sh     # 建立／重置環境：刪掉重建，所有修正和 ephemeral container 都會還原
./scripts/cleanup.sh   # 刪除整個 namespace
```

## 練習規則

1. **不要看 `app/App.java`**，把它當成拿不到原始碼的第三方應用。
2. 每題都照這個順序：**觀察 → 假設 → 選工具 → 拿證據 → 修正 → 驗證**。
   選工具時對照上面的〈怎麼選工具〉，先想清楚**為什麼**用這個工具，再動手。
3. 每題有三層提示，卡住再一層一層打開：
   - **提示 1**：往哪個方向想
   - **提示 2**：用什麼工具、看哪裡
   - **提示 3**：幾乎就是答案
4. 完整解答、實際輸出和解讀在 [SOLUTIONS.md](SOLUTIONS.md)。

---

## Lab 1：CrashLoopBackOff（`deploy/lab1`）

**症狀**：Pod 一直重啟，log 只有一行 `FATAL: unable to load configuration, exiting`。

**目標**：找出應用到底想讀哪個檔案、為什麼讀不到，再把它修好。

**過關條件**：lab1 Pod 變成 `1/1 Running`，log 出現 `listening on 0.0.0.0:8080`。

**建議工具**：`oc describe` → `oc debug deploy/lab1 --keep-annotations`
- 行程活不到 2 秒，`oc exec` 和 ephemeral container 都來不及連上去。
- `oc debug` 的副本有一樣的 env 和 volume，但不會 crash，可以慢慢看，也可以先試修正。

<details><summary>提示 1：往哪個方向想</summary>

- 先看退出碼。常見的有：
  | Exit code | 意思 |
  |---|---|
  | 137 | 被 SIGKILL，最常見是 OOMKilled |
  | 139 | segfault |
  | 143 | 被 SIGTERM（通常是 kubelet 正常關閉） |
  | 126 / 127 | 指令不能執行／找不到 |
  | 其他（1、2、3…） | **應用自己呼叫 exit**，原因要看應用的 log |
- log 說「讀不到設定」。那麼應用的設定檔路徑**是從哪裡決定的**？常見來源有啟動參數、環境變數，或寫死的預設路徑。
- 檔案「讀不到」通常只有三種原因：路徑不存在、權限不夠、內容格式錯。
</details>

<details><summary>提示 2：用什麼工具、看哪裡</summary>

- 很多資訊不用進容器就看得到：
  ```bash
  oc describe pod $(pod lab1)     # 看 Exit Code、Environment、Mounts 三段
  ```
  `Environment` 裡有沒有看起來像設定檔路徑的變數？`Mounts` 裡 ConfigMap 掛在哪裡？
- `oc logs --previous` 剛重啟時可能回 `unable to retrieve container logs`，等幾秒再試。
- 要親眼確認，就起一個不會 crash 的副本：
  ```bash
  oc debug deploy/lab1 --keep-annotations
  # 進去之後
  env | grep -i -E 'conf|app'
  ls -l <你懷疑的路徑>
  ls -la /config
  ```
- **一定要加 `--keep-annotations`**：`oc debug` 預設會拿掉 annotation。如果你能使用 `anyuid`（例如 cluster-admin），副本就會拿到 `anyuid`（UID 65532），
  跟原本 Pod 的 `restricted-v2`（隨機 UID）不一樣。如果問題跟權限有關，就會重現不出來。
</details>

<details><summary>提示 3：幾乎就是答案</summary>

- `APP_CONFIG` 指向 `/etc/app/app.properties`，但 `/etc/app` 根本不存在。
- ConfigMap `lab1-config` 實際掛在 `/config`，裡面就有 `app.properties`。
- 在副本裡先試修正：`oc debug deploy/lab1 --keep-annotations APP_CONFIG=/config/app.properties -- java /app/App.java`。
  看到 `listening` 就代表修正有效，再改 Deployment。
</details>

## Lab 2：Pod Ready，Service 卻連不上（`deploy/lab2`）

**症狀**：Pod 1/1 Running，但從別的 Pod 連 `lab2:8080` 被拒絕。

用這個指令重現：
```bash
oc run client --rm -i --restart=Never --image=$TOOLS -- curl -sv -m3 http://lab2:8080/
```

**目標**：證明問題出在應用本身，不在 Service、Endpoint 或網路。

**過關條件**：上面那個 client 指令回傳 `hello from lab2`。

**建議工具**：`oc get svc,endpoints` → `kubectl debug --target=app --image=$TOOLS`
- 行程活著，要看的是**它自己的 socket**，需要 `ss` 和 `curl`，但映像檔裡沒有。
- 不需要額外權限，ephemeral container 就夠了，不必上節點。
- 想練「完全沒有工具」的話：`oc exec` 讀 `/proc/net/tcp6` 也能得到一樣的答案。

<details><summary>提示 1：往哪個方向想</summary>

- 先分辨失敗的方式：
  - **馬上** `Connection refused`：封包到了某個主機，但那個 port 沒有人在聽，對方回了 RST。
  - **一直等到 timeout**：封包沒到，或被丟掉（路由、NetworkPolicy、防火牆）。
  - `Could not resolve host`：DNS 問題。
- 一個 Service 請求要經過：**DNS → Service IP:port → Endpoint（Pod IP:targetPort）→ 容器裡有沒有程式在聽**。
  一段一段確認，找出斷在哪一段。
</details>

<details><summary>提示 2：用什麼工具、看哪裡</summary>

- 前三段用 `oc` 就能確認：
  ```bash
  oc get svc lab2
  oc get endpoints lab2                       # 有沒有 endpoint？IP 是不是 Pod 的 IP？
  oc get pod $(pod lab2) -o jsonpath='{.status.podIP}'
  ```
  Endpoint 存在而且正確，代表 Service 這段沒問題，接下來要看**容器裡有沒有人在 Pod IP 的 8080 上聽**。
- 開 ephemeral container 進去：
  ```bash
  kubectl debug -it $(pod lab2) --image=$TOOLS --target=app --profile=restricted -c dbg -- bash
  ss -tlnp                        # 看 Local Address:Port 欄位
  curl -s 127.0.0.1:8080          # 從 localhost 連
  curl -s -m2 <podIP>:8080        # 從 Pod IP 連
  ```
  debug 容器和 app 共用 network namespace，所以 `ss` 看到的就是 app 的 socket。
  （本機的 `oc debug` 4.14 版沒有 `--target`，所以這裡用 `kubectl debug`。）
- 沒有工具時，解讀 `oc exec deploy/lab2 -c app -- cat /proc/net/tcp6`：
  - Java 預設開 IPv6 dual-stack socket，所以 IPv4 的 listen 會出現在 `tcp6`。
  - 第二欄 `local_address` 是 `位址:port`，都是 16 進位。
  - `0000000000000000FFFF0000xxxxxxxx` 是 IPv4-mapped 位址，最後 8 碼是 IPv4，**每兩碼一組倒過來讀**。
    例如 `0100007F` → `7F.00.00.01` → `127.0.0.1`。
  - port 不用倒：`1F90` → 8080。
  - `st` 欄 `0A` 代表 LISTEN。
</details>

<details><summary>提示 3：幾乎就是答案</summary>

- `ss` 顯示 `[::ffff:127.0.0.1]:8080`：只聽 loopback。從 `127.0.0.1` 連得到，從 Pod IP 連會被拒絕。
- 所以 Service、Endpoint 都沒錯，是應用 bind 錯位址。
- 假設你翻到這個應用的文件，上面寫著：「監聽位址由環境變數 `BIND_ADDR` 控制，預設 `127.0.0.1`」。
  （預設值寫在程式裡，所以 `env` 裡看不到這個變數。實務上這種資訊要看應用文件，或問開發者。）
</details>

## Lab 3：啟動 30 秒後變成 NotReady（`deploy/lab3`）

**症狀**：剛開始 Ready，大約 40 秒後 `READY 0/1`，readiness probe timeout。沒有任何錯誤 log。

**目標**：找出應用卡在哪裡，指出是哪兩個 thread、哪段程式碼。

**過關條件**：拿到 `Found one Java-level deadlock`，並說出是哪幾個 thread、各自拿著和在等哪把鎖。

**建議工具**：`oc exec deploy/lab3 -- jcmd 1 Thread.print`
- 行程活著，而且**映像檔本身就有 jcmd**，直接 `oc exec` 最快，不需要任何額外的容器。
- 如果映像檔只有 JRE、沒有 jcmd，就用 `kubectl debug --target=app --image=$JDK` 從另一個容器 attach。
- 連 shell 都沒有的話，從節點送 `kill -QUIT`，thread dump 會出現在 `oc logs` 裡。

<details><summary>提示 1：往哪個方向想</summary>

- 整理一下已知事實：
  - 沒有重啟 → 行程還活著。
  - probe 錯誤是 `context deadline exceeded`，不是 `connection refused` → port 有開、連線有建立，只是**請求沒有回應**。
  - 沒有錯誤 log → 應用自己也不知道出事了。
- 「行程活著、port 開著、請求卻不回」，常見原因有 deadlock、thread pool 用光、長時間 GC、卡在外部 I/O。
- 對 Java 應用來說，遇到「卡住」第一步永遠是 **thread dump**。
</details>

<details><summary>提示 2：用什麼工具、看哪裡</summary>

- 先看 probe 的設定和錯誤訊息：
  ```bash
  oc describe pod $(pod lab3) | grep -E 'Readiness|Unhealthy'
  ```
- 隔 10 秒抓兩次 thread dump：
  ```bash
  oc exec $(pod lab3) -- jcmd 1 Thread.print > td1.txt
  sleep 10
  oc exec $(pod lab3) -- jcmd 1 Thread.print > td2.txt
  ```
  如果同樣的 thread 停在同樣的地方，代表是「卡死」不是「很慢」。
- 讀 thread dump 的重點：
  - `java.lang.Thread.State: BLOCKED (on object monitor)` 的 thread
  - 每個 thread 底下的 `- locked <0x...>` 和 `- waiting to lock <0x...>`，對照同一個位址
  - **最後面**，JVM 偵測到 deadlock 會自動印 `Found one Java-level deadlock` 的摘要
</details>

<details><summary>提示 3：幾乎就是答案</summary>

- `cache-refresher` 拿著鎖 A、在等鎖 B；`metrics-flusher` 拿著鎖 B、在等鎖 A，兩個互相等。
- HTTP worker（`pool-1-thread-N`）處理 `/healthz` 時也要拿鎖 A，所以每個 probe 請求都卡住。
- 每 5 秒一次 probe 就多一條卡住的 thread。數一下 `pool-1-thread-` 有幾條，會隨時間一直增加。
- thread dump 裡有行號（`App.java:67`、`:77`、`:83`），拿得到原始碼的話可以直接告訴開發者是哪幾行。
</details>

## Lab 4：每隔幾分鐘就 OOMKilled（`deploy/lab4`）

**症狀**：大約每 5 分鐘重啟一次，`lastState.terminated.reason: OOMKilled`、exit 137。
但 Java heap 看起來不大，也沒有 `java.lang.OutOfMemoryError`。

**目標**：找出記憶體是誰吃掉的、為什麼 JVM 沒有自己擋下來，再把 heap dump 拿回本機。

**過關條件**：說出 NMT 裡成長的是哪一類記憶體；`heap.hprof` 成功拿回本機。
進階：調整設定後，讓 log 改成出現 Java 的 `OutOfMemoryError`。

**建議工具**
- **分析記憶體**：`oc exec` + `jcmd`、`/sys/fs/cgroup`。映像檔裡都有，不需要額外的容器。
- **拿出 heap dump**：`kubectl debug --target=app --image=$TOOLS`，再 `oc cp -c dbg <pod>:/proc/1/root/...`。
  `oc cp` 需要容器裡有 tar，app 容器沒有；debug 容器有 tar，而且可以透過 `/proc/1/root` 讀到 app 的檔案。

<details><summary>提示 1：往哪個方向想</summary>

- `OOMKilled` + exit 137 是 **Linux kernel 的 cgroup OOM killer** 殺的，不是 JVM 自己掛掉。
  如果是 Java heap 不夠，JVM 會先丟 `java.lang.OutOfMemoryError`，log 裡會有 stack trace。
- 所以這題的問題是：**container 的記憶體用量超過 limit，但 JVM 覺得自己還沒超過任何上限**。
- JVM 的記憶體不只 heap，還有 metaspace、thread stack、code cache、GC 結構、direct buffer、JNI malloc 等等。
  要找出是哪一塊在長。
</details>

<details><summary>提示 2：用什麼工具、看哪裡</summary>

- 先用三個數字建立整體概念：
  ```bash
  oc exec $(pod lab4) -c app -- bash -c 'cat /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.current; grep VmRSS /proc/1/status'
  oc exec $(pod lab4) -c app -- jcmd 1 GC.heap_info
  ```
  container 用了多少？heap 用了多少？差很多的話，差額就在 heap 以外。
- 這個 Deployment 已經用 `JAVA_TOOL_OPTIONS` 開了 NMT（`oc get deploy lab4 -o yaml` 可以看到）。
  實務上 NMT 要在 JVM 啟動時就開，**事後不能補開**。
  ```bash
  oc exec $(pod lab4) -c app -- jcmd 1 VM.native_memory baseline
  # 等 60 秒
  oc exec $(pod lab4) -c app -- jcmd 1 VM.native_memory summary.diff
  ```
  找後面帶 `+xxxKB` 的那一類，成長速度跟 log 的 `active sessions` 有沒有關聯？
- 看 JVM 實際用的上限：
  ```bash
  oc exec $(pod lab4) -c app -- jcmd 1 VM.flags | tr ' ' '\n' | grep -E 'MaxHeapSize|MaxDirect'
  ```
- 不開 heap dump 也能數物件：`jcmd 1 GC.class_histogram | grep -E 'num|DirectByteBuffer'`，隔一分鐘再跑一次比較。
- 拿 heap dump：
  ```bash
  POD=$(pod lab4)
  oc exec $POD -c app -- jcmd 1 GC.heap_dump /tmp/heap.hprof
  oc cp -c app $POD:/tmp/heap.hprof ./heap.hprof      # 先試試看，會失敗
  kubectl debug $POD --image=$TOOLS --target=app --profile=restricted -c dbg -- sleep infinity
  oc cp -c dbg $POD:/proc/1/root/tmp/heap.hprof ./heap.hprof
  ```
</details>

<details><summary>提示 3：幾乎就是答案</summary>

- NMT 裡一直長的是 `Other`，大約每分鐘 +60MB。`ByteBuffer.allocateDirect()` 配置的記憶體就算在這一類。
- `class_histogram` 裡 `java.nio.DirectByteBuffer` 的數量一直增加，但它在 heap 上只佔幾 KB：
  heap 上只是一個小小的物件，真正的 1 MiB 在 native 記憶體。所以 heap 看起來很小。
- `MaxDirectMemorySize=2g` 遠大於 container limit 384Mi。JVM 要到 2g 才會丟錯，但 cgroup 在 384Mi 就先把行程殺了。
- 進階：把 `MaxDirectMemorySize` 調到 limit 以內（例如 `64m`），觀察 log 和 Pod 狀態會變成什麼樣子。**結果可能跟你想的不一樣。**
</details>

## Lab 5：前端一直報 `upstream unavailable`（`deploy/lab5` → `deploy/backend`）

**症狀**：lab5 每 5 秒印一次 `ERROR: upstream unavailable`，沒有其他細節。backend Pod 是 Running 的。

**目標**：從封包證明連線失敗的方式（timeout？refused？DNS？），找出設定錯在哪裡。

**過關條件**：用 tcpdump 看到失敗的封包；修正後 lab5 的 log 變成 `upstream ok: pong`。

**建議工具**（依步驟）
1. **重現連線**：`oc exec` + bash 的 `/dev/tcp`、`getent`。映像檔裡就有，不需要 curl。
2. **確認 backend 實際聽的 port**：`kubectl debug --target=app --image=$TOOLS` 跑 `ss`。
3. **抓封包**：`oc debug node` + `nsenter -n` + `tcpdump`。
   tcpdump 需要 NET_ADMIN，restricted-v2 不允許 ephemeral container 加 capability（可以試一次 `--profile=netadmin`，看它怎麼被拒絕），
   所以只能從節點層抓。

<details><summary>提示 1：往哪個方向想</summary>

- log 只說 `upstream unavailable`，資訊太少。第一步是自己重現一次連線，看**失敗的方式**是哪一種：
  DNS 解析不到、timeout，還是 refused。三種原因完全不同。
- 這個映像檔沒有 curl，但有 bash 和 getent（參考下方〈沒有 curl 時的替代方案〉）。
- 確定失敗方式之後，跟 Lab 2 一樣沿著 **DNS → Service → Endpoint → 容器** 一段一段查。
</details>

<details><summary>提示 2：用什麼工具、看哪裡</summary>

- 從 lab5 容器裡重現：
  ```bash
  oc exec deploy/lab5 -- getent hosts backend
  oc exec deploy/lab5 -- bash -c 'time (exec 3<>/dev/tcp/backend/9091)'
  ```
  `time` 很短就失敗表示 refused，接近 timeout 才失敗表示封包被丟掉。
- 逐段比對 port（**這題的重點是 port**）：
  ```bash
  oc get svc backend -o jsonpath='{.spec.ports}'            # port / targetPort
  oc get endpoints backend                                   # Pod IP:哪個 port
  oc get pod $(pod backend) -o jsonpath='{.spec.containers[0].ports}'
  kubectl debug -it $(pod backend) --image=$TOOLS --target=app --profile=restricted -c dbg -- ss -tlnp
  ```
  `containerPort` 只是宣告用，不代表程式真的聽那裡，要用 `ss` 確認。
- 從節點抓封包：
  ```bash
  oc debug node/$(oc get nodes -o name | head -1 | cut -d/ -f2)
  # 以下在 debug pod 裡執行
  POD=$(chroot /host crictl pods --namespace hi-debug-lab --label app=lab5 --state ready -q | head -1)
  CID=$(chroot /host crictl ps --pod $POD --name app -q | head -1)
  PID=$(chroot /host crictl inspect --output go-template --template '{{.info.pid}}' $CID)
  nsenter -t $PID -n -- tcpdump -nn -i eth0 tcp
  ```
  `crictl` 在主機上，所以要 `chroot /host`；`tcpdump` 用 debug pod 自己的（RHCOS 主機上沒有 tcpdump）。
  注意 `Flags [S]`（SYN）之後回來的是什麼：`[S.]` 是 SYN-ACK，`[R.]` 是 RST；只看到 `[S]` 一直重送就是 timeout。
</details>

<details><summary>提示 3：幾乎就是答案</summary>

- tcpdump：`lab5 → 172.30.x.x.9091 [S]`，緊接著 `172.30.x.x.9091 → lab5 [R.]`。封包有到，但那個 port 沒有人聽。
- Service `backend` 的 `port: 9091, targetPort: 9091`，所以流量被轉到 backend Pod 的 9091。
- 但 backend 實際聽的是 `*:9090`。
- 修 Service 的 `targetPort`，不用改 frontend（它連的 9091 是 Service 的 port，這部分沒錯）。
</details>

---

## 沒有 curl 時的替代方案

hi/openjdk 沒有 curl、nc、dig，但有 bash、getent 和 JDK，可以這樣替代：

| 需求 | 做法 | 限制 |
|---|---|---|
| 確認 port 通不通 | `bash -c 'timeout 2 bash -c "</dev/tcp/backend/9091" && echo open \|\| echo closed'` | 需要 bash |
| 送 HTTP 請求 | bash 的 `/dev/tcp`（見下方） | 只能用純 TCP，不支援 HTTPS |
| 查 DNS | `getent hosts backend` | 只會回傳解析結果，看不到 DNS 封包 |
| HTTP/HTTPS 都要 | Java 單檔程式（見下方） | 需要 JDK；HTTPS 用的是應用自己的 truststore |
| 從叢集外打 | `oc port-forward` + 本機 curl | 會在 Pod 的 network namespace 裡連線 |
| 什麼工具都不用 | `oc get --raw .../pods/<pod>:<port>/proxy/<path>` | 要有 API server proxy 權限，只能用 HTTP |
| 要 `curl -v`、`-k`、`--resolve` | ephemeral container：`kubectl debug -it <pod名稱> --image=$TOOLS --target=app --profile=restricted -- curl -v ...` | 需要能建立 ephemeral container |

**bash `/dev/tcp` 送 HTTP**
```bash
oc exec deploy/lab5 -- bash -c '
exec 3<>/dev/tcp/backend/9091
printf "GET /ping HTTP/1.0\r\nHost: backend\r\n\r\n" >&3
cat <&3'
```
失敗方式本身就是線索：馬上出現 `Connection refused` 表示目的端存在、但那個 port 沒人聽；一直等到 timeout 表示網路不通或被 NetworkPolicy 擋住。

**Java 單檔 curl（JDK 25 不必宣告 class）**
```bash
oc exec deploy/lab5 -- bash -c 'cat > /tmp/Get.java <<EOF
void main(String[] a) throws Exception {
  var r = java.net.http.HttpClient.newHttpClient().send(
    java.net.http.HttpRequest.newBuilder(java.net.URI.create(a[0])).build(),
    java.net.http.HttpResponse.BodyHandlers.ofString());
  System.out.println(r.statusCode() + " " + r.body());
}
EOF
java /tmp/Get.java http://backend:9091/ping'
```
打叢集內部用自簽 CA 的 HTTPS 服務時，如果出現 `PKIX path building failed`，代表**應用的 truststore 不信任那個 CA**。正式環境的應用很常遇到這個問題。

**API server proxy**
```bash
oc get --raw "/api/v1/namespaces/hi-debug-lab/pods/<pod名稱>:9090/proxy/ping"
oc get --raw "/api/v1/namespaces/hi-debug-lab/services/backend:9091/proxy/ping"
```

> 連 bash 都沒有的映像檔，`/dev/tcp` 和 Java 單檔這兩招都不能用，只剩 port-forward、API proxy 和 ephemeral container。

---

## 速查表：情境 → 工具

| 情境 | 工具 | 指令 |
|---|---|---|
| Pod 一直 crash，要看設定 | `oc describe` | `oc describe pod <pod>`（看 Exit Code、Environment、Mounts） |
| Pod 一直 crash，要進去看、先試修正 | `oc debug` 副本 | `oc debug deploy/<name> --keep-annotations [KEY=VALUE] [-- <啟動指令>]` |
| 行程活著，映像檔有需要的工具 | `oc exec` | `oc exec <pod> -- jcmd 1 Thread.print` |
| 行程活著，缺 ss / curl / strace | ephemeral | `kubectl debug -it <pod> --image=$TOOLS --target=app --profile=restricted -- bash` |
| JVM 診斷，但映像檔沒有 jcmd | ephemeral | `kubectl debug -it <pod> --image=$JDK --target=app --profile=restricted -- jcmd 1 <command>` |
| 讀目標的檔案、環境變數 | ephemeral | `/proc/1/root/...`、`/proc/1/environ`（用 `tr '\0' '\n'` 換行） |
| 從目標複製檔案出來（沒有 tar） | ephemeral | `oc cp -c <debug容器> <pod>:/proc/1/root/<path> ./` |
| 抓封包、kernel log、需要 root | 節點 | `oc debug node/<node>` → `crictl inspect` 拿 PID → `nsenter -t <PID> -n` |
| 什麼工具都不能用 | `oc exec` | `/proc/1/{environ,cmdline,status,fd,net/tcp6}`、bash `/dev/tcp`、`kill -3 1`（Java thread dump） |

## 注意事項

- Ephemeral container **加了就移除不掉**，要等 Pod 重建才會消失（`./scripts/setup.sh` 會刪掉重建）。
- 同一個 Pod 再開 ephemeral container 時，`-c` 的名字不能重複（例如改成 `dbg2`）。
- 用 `oc set env` 或 `oc patch` 修正後 Pod 會重建，舊 Pod 上的 ephemeral container 會跟著消失。
- 在 restricted-v2 底下，ephemeral container 會自動拿到**跟 app 一樣的 UID**，所以能 ptrace、能讀 `/proc/1/root`、能用 jcmd attach。
  如果 UID 不同，又沒有 `CAP_SYS_PTRACE`，這些都會失敗。
- lab 的 Pod 用 `openshift.io/required-scc: restricted-v2` annotation 強制指定 SCC，確保權限跟一般應用一樣。
  你自己用 `oc debug` 或 `oc run` 建的 Pod 不受這個 annotation 影響。如果你能使用 `anyuid`（例如 cluster-admin），它們就會拿到 `anyuid`，除非加 `--keep-annotations`。

# 參考解答

> 以下輸出都是在測試叢集（OCP 4.18 單節點）上實際跑過的結果。UID、IP、PID、物件位址會因為環境不同而不一樣，看格式和意思就好。
> 指令裡的 `$TOOLS`、`$JDK`、`pod()` 請先照 README〈工具映像檔〉那段設定好。

每題都分成：**一句話原因 → 選用的工具和理由 → 逐步診斷（指令、輸出、怎麼解讀）→ 修正 → 驗證 → 延伸重點**。

工具選擇的原則（詳見 README〈怎麼選工具〉）：
**`oc exec` 能做就用 `oc exec`；缺工具才開 ephemeral container；行程活不久才用 `oc debug` 副本；需要特權才上節點。**

---

## Lab 1：設定檔路徑和掛載點不一致

**一句話原因**：`APP_CONFIG` 指向 `/etc/app/app.properties`，但 ConfigMap 掛在 `/config`。

**選用的工具**
| 步驟 | 工具 | 理由 |
|---|---|---|
| 找原因 | `oc describe` | env 和 mounts 都寫在 Pod spec 裡，不用進容器就看得到 |
| 親眼確認、先試修正 | `oc debug deploy/lab1 --keep-annotations` | 行程活不到 2 秒，`oc exec` 和 ephemeral container 都來不及連上；副本不會 crash |

### 步驟 1：確認是哪一種 crash

```bash
oc get pods -l app=lab1
```
```
NAME                  READY   STATUS   RESTARTS      AGE
lab1-9b9477c6-fgmmv   0/1     Error    2 (30s ago)   51s
```
`STATUS` 會在 `Error` 和 `CrashLoopBackOff` 之間切換，`RESTARTS` 一直增加。

```bash
oc describe pod $(pod lab1)
```
```
    State:          Terminated
      Reason:       Error
      Exit Code:    3
      Started:      Wed, 11 Nov 2026 09:20:11 +0800
      Finished:     Wed, 11 Nov 2026 09:20:13 +0800
    ...
    Environment:
      LAB:         1
      APP_CONFIG:  /etc/app/app.properties
    Mounts:
      /app from src (rw)
      /config from config (rw)
```
**解讀**
- `Exit Code: 3`：不是 137（OOM）、139（segfault），是應用自己呼叫 `exit(3)`。
- `Started` 到 `Finished` 只有 2 秒：一啟動就結束，跟 log 裡的 `FATAL` 吻合。
- **答案其實已經在畫面上了**：`Environment` 寫的是 `/etc/app/...`，`Mounts` 卻是 `/config`。
  很多問題光看 `describe` 就能解決，不需要進容器。

### 步驟 2：看 log

```bash
oc logs $(pod lab1) --previous
```
```
01:31:29 starting, mode=1
01:31:29 FATAL: unable to load configuration, exiting
```
如果出現 `unable to retrieve container logs for cri-o://...`，是因為容器剛好在重啟，等幾秒再試。

### 步驟 3：進一個不會 crash 的副本確認

```bash
oc debug deploy/lab1 --keep-annotations -- bash -c 'id; env | grep -E "^(APP_CONFIG|LAB)="; ls -l /etc/app; ls -la /config; cat /config/app.properties'
```
```
uid=1000860000(1000860000) gid=0(root) groups=0(root),1000860000
LAB=1
APP_CONFIG=/etc/app/app.properties
ls: cannot access '/etc/app': No such file or directory
total 0
drwxrwxrwx. 3 root root 82 Nov 11 01:20 .
drwxr-xr-x. 2 root root 28 Nov 11 01:20 ..2026_11_11_01_20_25.2615401503
lrwxrwxrwx. 1 root root 21 Nov 11 01:20 app.properties -> ..data/app.properties
lrwxrwxrwx. 1 root root 32 Nov 11 01:20 ..data -> ..2026_11_11_01_20_25.2615401503
greeting=hello from lab1
```
**解讀**
- `/etc/app` 不存在，證實了假設。
- `/config/app.properties` 存在，內容也正確。
- ConfigMap 掛載出來是 **symlink → `..data` → 時間戳目錄**。這是 kubelet 用來做原子更新的機制，看到 symlink 是正常的。

**為什麼一定要加 `--keep-annotations`**
```bash
oc debug deploy/lab1 -- id
oc debug deploy/lab1 --keep-annotations -- id
```
```
uid=65532(65532) gid=0(root) groups=0(root)                       ← 預設：anyuid
uid=1000860000(1000860000) gid=0(root) groups=0(root),1000860000  ← 保留 annotation：restricted-v2
```
`oc debug` 預設會拿掉 annotation，`openshift.io/required-scc` 也一起被拿掉了。副本又是用你的身分建立的，如果你能使用 `anyuid`（例如 cluster-admin），副本就會拿到 `anyuid`。
`oc debug deploy/lab1 -o yaml` 可以看到，副本 Pod 只剩 `debug.openshift.io/*` 這兩個 annotation。
如果問題跟權限有關，不加這個選項就會重現不出來。

### 步驟 4：在副本裡重現，再先試修正

重跑原本的啟動指令：
```bash
oc get deploy lab1 -o jsonpath='{..command}'; echo        # ["java","/app/App.java"]
oc debug deploy/lab1 --keep-annotations -- bash -c 'java /app/App.java; echo "exit=$?"'
```
```
01:56:45 FATAL: unable to load configuration, exiting
exit=3
```
用 `KEY=VALUE` 覆寫環境變數，**不改 Deployment** 先試修正：
```bash
oc debug deploy/lab1 --keep-annotations APP_CONFIG=/config/app.properties -- bash -c 'timeout 8 java /app/App.java; echo "exit=$?"'
```
```
01:56:53 starting, mode=1
01:56:53 listening on 0.0.0.0:8080
exit=124            ← 被 timeout 8 秒結束，代表 app 正常跑起來了
```

### 修正

```bash
oc set env deploy/lab1 APP_CONFIG=/config/app.properties
```
（另一種修法：把 Deployment 裡 config volume 的 `mountPath` 改成 `/etc/app`。）

### 驗證

```bash
oc rollout status deploy/lab1
oc logs deploy/lab1
```
```
01:20:52 starting, mode=1
01:20:52 listening on 0.0.0.0:8080
```

### 延伸重點
- 診斷 CrashLoopBackOff 的順序：**exit code → `logs --previous` → describe 的 env/mounts → `oc debug` 副本**。
- 映像檔**連 shell 都沒有**的時候，`oc debug deploy/lab1` 預設會失敗（它要跑 shell）。改用
  `oc debug deploy/lab1 --keep-annotations --image=$TOOLS`：映像檔換成工具箱，env 和 volume 照樣保留。
  ```
  APP_CONFIG=/etc/app/app.properties
  /config: app.properties
  ls: cannot access '/etc/app': No such file or directory
  ```

---

## Lab 2：應用只 bind 在 127.0.0.1

**一句話原因**：應用預設監聽 `127.0.0.1:8080`，只有 Pod 內部的 loopback 連得到。

**選用的工具**
| 步驟 | 工具 | 理由 |
|---|---|---|
| 檢查 Service、Endpoint | `oc get` | 都是 API 物件，不用進容器 |
| 看 app 的 socket | `kubectl debug --target=app --image=$TOOLS` | 行程活著，需要 `ss`、`curl`，映像檔裡沒有；不需要特權，所以不必上節點 |
| （練習用）完全不用工具 | `oc exec` + `/proc/net/tcp6` | 只需要 `cat` |

### 步驟 1：重現，看失敗方式

```bash
oc run client --rm -i --restart=Never --image=$TOOLS -- curl -sv -m3 http://lab2:8080/
```
```
*   Trying 172.30.199.25:8080...
* connect to 172.30.199.25 port 8080 failed: Connection refused
* Failed to connect to lab2 port 8080: Connection refused
```
**解讀**
- DNS 有解析出 Service IP `172.30.199.25`，所以 DNS 沒問題。
- **馬上** `Connection refused`，不是 timeout：封包到了某個地方，對方回了 RST。

### 步驟 2：檢查 Service 和 Endpoint

```bash
oc get svc lab2
oc get endpoints lab2
oc get pod $(pod lab2) -o jsonpath='{.status.podIP}'
```
```
NAME   TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)    AGE
lab2   ClusterIP   172.30.199.25   <none>        8080/TCP   88s
NAME   ENDPOINTS           AGE
lab2   10.128.0.135:8080   88s
10.128.0.135
```
**解讀**：Endpoint 存在，指向正確的 Pod IP 和 port。Service 這一段沒問題。
接下來要回答的是：**Pod 裡有沒有程式在 `10.128.0.135:8080` 上聽？**

### 步驟 3：開 ephemeral container 看 socket

```bash
kubectl debug -it $(pod lab2) --image=$TOOLS --target=app --profile=restricted -c dbg -- bash
```
在 debug 容器裡：
```bash
ps -ef
```
```
UID          PID    PPID  C STIME TTY          TIME CMD
1000810+       1       0  2 01:19 ?        00:00:02 java /app/App.java
1000810+      24       0  0 01:21 ?        00:00:00 bash
```
看得到 PID 1 是 app 的 java，表示 `--target=app` 成功共用了 PID namespace。UID 跟 app 一樣。

```bash
ss -tlnp
```
```
State  Recv-Q Send-Q      Local Address:Port Peer Address:PortProcess
LISTEN 0      0      [::ffff:127.0.0.1]:8080            *:*    users:(("java",pid=1,fd=6))
```
**解讀**：`Local Address` 是 `::ffff:127.0.0.1`，也就是用 IPv4-mapped IPv6 表示的 `127.0.0.1`。
只聽 loopback，從 Pod IP 進來的連線不會被接受。正常應該是 `*:8080` 或 `0.0.0.0:8080`。

```bash
curl -s 127.0.0.1:8080; echo
curl -s -m2 10.128.0.135:8080 || echo "curl exit=$?"
```
```
hello from lab2
curl exit=7
```
curl exit 7 就是 `Failed to connect`。兩個結果放在一起，證據就完整了。

順便看一下應用的環境變數和啟動指令（沒有 `ps` 也能用這招）：
```bash
cat /proc/1/environ | tr '\0' '\n' | grep -E 'LAB|BIND|JAVA'
cat /proc/1/cmdline | tr '\0' ' '; echo
```
```
LAB=2
JAVA_HOME=/usr/lib/jvm/java-25-openjdk
JAVA_VERSION=25.0.4.1.1
java /app/App.java
```
環境變數裡沒有 `BIND_ADDR`，所以這個應用用的是程式裡寫死的預設值。

### 步驟 3'：完全沒有工具時

```bash
oc exec $(pod lab2) -c app -- cat /proc/net/tcp6
```
```
  sl  local_address                         remote_address                        st ...  uid
   0: 0000000000000000FFFF00000100007F:1F90 00000000000000000000000000000000:0000 0A ... 1000810000
```
**解讀**
| 欄位 | 值 | 意思 |
|---|---|---|
| local_address 位址 | `0000000000000000FFFF0000` + `0100007F` | IPv4-mapped，後 8 碼兩兩倒過來讀：`7F 00 00 01` → `127.0.0.1` |
| local_address port | `1F90` | 16 進位轉 10 進位：8080（port 不用倒） |
| st | `0A` | LISTEN（`01` 是 ESTABLISHED） |
| uid | `1000810000` | 這個 socket 屬於哪個 UID |

如果是 `0.0.0.0` 或 `::`，位址會是全 0：`00000000000000000000000000000000:1F90`。

### 修正

應用文件說監聽位址由 `BIND_ADDR` 控制：
```bash
oc set env deploy/lab2 BIND_ADDR=0.0.0.0
```

### 驗證

```bash
oc rollout status deploy/lab2
oc run client --rm -i --restart=Never --image=$TOOLS -- curl -s -m3 http://lab2:8080/
```
```
hello from lab2
```

### 延伸重點
- 這個 Deployment 沒有設 readiness probe，所以 Pod 顯示 Ready。**Ready 只代表 probe 通過，不代表外面連得到。**
  如果加了 `tcpSocket` 或 `httpGet` probe，kubelet 是從 Pod IP 去 probe，會失敗，Pod 就會變成 NotReady。
- `oc port-forward deploy/lab2 18080:8080` 連得到，因為 port-forward 是在 Pod 的 network namespace 裡連 localhost。
  「port-forward 通、Service 不通」本身就是 bind 位址錯誤的典型特徵。

---

## Lab 3：Java deadlock

**一句話原因**：兩個背景 thread 用相反順序拿兩把鎖，形成 deadlock；`/healthz` 也要拿其中一把鎖，所以 probe 一直 timeout。

**選用的工具**
| 情況 | 工具 | 理由 |
|---|---|---|
| **這題**：映像檔有 jcmd | `oc exec ... jcmd 1 Thread.print` | 工具就在映像檔裡，最快，不需要任何額外的容器 |
| 映像檔只有 JRE，沒有 jcmd | `kubectl debug --target=app --image=$JDK` | 從另一個容器 attach，條件見下方〈如果映像檔沒有 jcmd〉 |
| 連 shell 都沒有 | 從節點送 `kill -QUIT` | 不需要 attach，thread dump 會印到 `oc logs` |

### 步驟 1：整理症狀

```bash
oc get pods -l app=lab3
oc describe pod $(pod lab3) | grep -E 'Readiness|Unhealthy'
oc logs $(pod lab3)
```
```
NAME                    READY   STATUS    RESTARTS   AGE
lab3-5dcc7d5544-z8qzg   0/1     Running   0          2m16s

    Readiness:      http-get http://:8080/healthz delay=10s timeout=2s period=5s #success=1 #failure=3
  Warning  Unhealthy  1s (x19 over 83s)  kubelet  Readiness probe failed: Get "http://10.128.0.136:8080/healthz": context deadline exceeded (Client.Timeout exceeded while awaiting headers)

01:19:51 starting, mode=3
01:19:51 listening on 0.0.0.0:8080
```
**解讀**
- `RESTARTS 0`：行程沒死。
- `context deadline exceeded (... awaiting headers)`：TCP 連線**建立成功**，但 2 秒內沒收到 HTTP 回應。
  如果 port 沒開，錯誤會是 `connection refused`。
- log 在 listening 之後就沒有任何東西了，應用不知道自己出事。
- 結論：**應用卡住了** → 要看 thread dump。

### 步驟 2：抓 thread dump

映像檔裡有 jcmd，直接 `oc exec`：
```bash
oc exec $(pod lab3) -- jcmd 1 Thread.print > td1.txt
sleep 10
oc exec $(pod lab3) -- jcmd 1 Thread.print > td2.txt
sed -n '/Found one Java-level deadlock/,$p' td2.txt
```
```
Found one Java-level deadlock:
=============================
"pool-1-thread-1":
  waiting to lock monitor 0x00007f9ef8000ee0 (object 0x0000000713008268, a java.lang.Object),
  which is held by "cache-refresher"

"cache-refresher":
  waiting to lock monitor 0x00007f9f04000ee0 (object 0x0000000713000408, a java.lang.Object),
  which is held by "metrics-flusher"

"metrics-flusher":
  waiting to lock monitor 0x00007f9ef8000ee0 (object 0x0000000713008268, a java.lang.Object),
  which is held by "cache-refresher"

Java stack information for the threads listed above:
===================================================
"pool-1-thread-1":
	at App.lambda$deadlockApp$0(App.java:67)
	- waiting to lock <0x0000000713008268> (a java.lang.Object)
	...
	at sun.net.httpserver.ServerImpl$Exchange.run(jdk.httpserver@25.0.4.1/ServerImpl.java:900)
"cache-refresher":
	at App.lambda$deadlockApp$1(App.java:77)
	- waiting to lock <0x0000000713000408> (a java.lang.Object)
	- locked <0x0000000713008268> (a java.lang.Object)
"metrics-flusher":
	at App.lambda$deadlockApp$2(App.java:83)
	- waiting to lock <0x0000000713008268> (a java.lang.Object)
	- locked <0x0000000713000408> (a java.lang.Object)
```
**解讀**：把物件位址當成鎖的名字。

| Thread | 拿著（locked） | 在等（waiting to lock） | 位置 |
|---|---|---|---|
| `cache-refresher` | `...8268`（鎖 A） | `...0408`（鎖 B） | App.java:77 |
| `metrics-flusher` | `...0408`（鎖 B） | `...8268`（鎖 A） | App.java:83 |
| `pool-1-thread-N`（HTTP `/healthz`） | 無 | `...8268`（鎖 A） | App.java:67 |

- A 等 B、B 等 A，這就是典型的**鎖順序反轉（lock ordering）deadlock**。
- HTTP worker 的 stack 裡有 `sun.net.httpserver.ServerImpl$Exchange.run`，可以確認它是在處理 HTTP 請求。
- 比對 td1 和 td2：同樣的 thread 停在同樣的行號，代表是永久卡死，不是暫時變慢。

### 步驟 3：觀察副作用

```bash
grep -c '^"pool-1-thread' td2.txt
```
```
26
```
每 5 秒一次 probe 就多一條卡住的 thread。放著不管的話，thread 數會一直長，最後可能因為 thread 太多耗光記憶體。

### 如果映像檔沒有 jcmd：從另一個容器 attach

只有 JRE 的映像檔沒有 jcmd。這時用**跟目標相同 JDK 版本**的映像檔開 ephemeral container：
```bash
kubectl debug -it $(pod lab3) --image=$JDK --target=app --profile=restricted -c jdk -- bash
jcmd
```
```
1 jdk.compiler/com.sun.tools.javac.launcher.SourceLauncher /app/App.java
58 jdk.jcmd/sun.tools.jcmd.JCmd
```
看得到 PID 1，接著就能 `jcmd 1 Thread.print`。跨容器 attach 要同時滿足三個條件：
```bash
id
ls -la /proc/1/root/tmp/
```
```
uid=1000810000(1000810000) gid=0(root) groups=0(root),1000810000
drwxr-xr-x. 2 1000810000 root 15 Nov 11 01:19 hsperfdata_1000810000
srw-------. 1 1000810000 root  0 Nov 11 01:22 .java_pid1
```
1. **共用 PID namespace**（`--target=app`）：看得到 PID 1，才能送 SIGQUIT 觸發 attach。
2. **同一個 UID**：`.java_pid1` 是 `srw-------`，只有擁有者能連。restricted-v2 讓兩個容器拿到同一個 UID。
3. **透過 `/proc/1/root` 找 socket**：jcmd 會到 `/proc/1/root/tmp/` 找 `.java_pid1`。
   `jcmd` 不帶參數時之所以列得出 PID 1，是因為它讀了 `/proc/1/root/tmp/hsperfdata_<uid>/`。

### 如果連 shell 都沒有：`kill -3`

JVM 收到 SIGQUIT 時，會把完整的 thread dump 印到 **stdout**，行程不會結束。在這個映像檔裡可以這樣驗證：
```bash
oc exec $(pod lab3) -- bash -c 'kill -3 1'
oc logs $(pod lab3) | grep -E 'Full thread dump|Found one Java-level deadlock'
```
```
Full thread dump OpenJDK 64-Bit Server VM (25.0.4.1+1 mixed mode, sharing):
Found one Java-level deadlock:
```
沒有 shell 的映像檔，就從節點（`oc debug node`）對主機 PID 送 `kill -QUIT`，結果一樣。
缺點是 dump 會混在應用 log 裡；如果 log 有被收集到外部系統，dump 也會被送過去。

### 修正

這題要改程式（兩個 thread 用相同順序拿鎖，或改用 `tryLock` 加 timeout）。重點是**在拿不到原始碼、不能重啟的情況下**，
把「哪兩個 thread、哪兩把鎖、哪幾行」這些證據交給開發者。

暫時止血：`oc rollout restart deploy/lab3`。不過 30 秒後又會卡住。

### 延伸重點
- 這題的 readiness probe 失敗只會讓 Pod 離開 Service，**不會重啟**。如果有設 liveness probe，Pod 就會被重啟，
  thread dump 的證據也會跟著消失。所以遇到 liveness 一直重啟的 Java 應用，要先暫時拿掉 liveness，或放寬它的 timeout，才抓得到 dump。

---

## Lab 4：direct memory 洩漏，被 kernel OOM kill

**一句話原因**：程式一直配置 direct buffer 不釋放，而 `MaxDirectMemorySize=2g` 遠大於 container limit 384Mi，
所以 JVM 永遠不會丟錯，最後被 cgroup OOM killer 殺掉。

**選用的工具**
| 步驟 | 工具 | 理由 |
|---|---|---|
| 確認 OOMKilled | `oc get` / `oc describe` | 寫在 Pod status 裡 |
| 分析記憶體 | `oc exec` + `jcmd`、`/sys/fs/cgroup` | 映像檔裡就有 jcmd、cat，不需要額外的容器 |
| 拿出 heap dump | `kubectl debug --target=app --image=$TOOLS` + `oc cp` | `oc cp` 需要 tar，app 容器沒有；debug 容器有 tar，又能透過 `/proc/1/root` 讀到 app 的檔案 |
| （需要更多證據時）kernel 的 OOM 紀錄 | `oc debug node` | kernel log 只有節點上看得到 |

### 步驟 1：確認是誰殺的

```bash
oc describe pod $(pod lab4) | grep -A6 'Last State'
```
```
    Last State:     Terminated
      Reason:       OOMKilled
      Exit Code:    137
      Started:      Wed, 11 Nov 2026 09:19:44 +0800
      Finished:     Wed, 11 Nov 2026 09:25:16 +0800
```
```bash
oc logs $(pod lab4) --previous | tail -2
```
```
01:24:19 active sessions: 270
01:24:49 active sessions: 300
```
**解讀**
- `OOMKilled` + 137：kernel 用 SIGKILL 殺的。JVM 沒機會印任何東西，所以 log 停在正常的訊息。
- 從 Started 到 Finished 大約 5.5 分鐘，log 最後是 300 sessions。先記下這個關聯。

### 步驟 2：比較 container 用量和 heap

```bash
oc exec $(pod lab4) -c app -- bash -c 'cat /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.current; grep VmRSS /proc/1/status'
```
```
402653184        ← limit：384 MiB
227708928        ← 目前用量：約 217 MiB
VmRSS:	  253860 kB
```
```bash
oc exec $(pod lab4) -c app -- jcmd 1 GC.heap_info
```
```
DefNew     total 2432K, used 289K ...
Tenured    total 9152K, used 9041K ...
```
**解讀**：container 用了 200 多 MiB，heap 卻只用了大約 9 MiB。**記憶體大部分在 heap 以外**。

### 步驟 3：看 JVM 設定

```bash
oc exec $(pod lab4) -c app -- jcmd 1 VM.flags | tr ' ' '\n' | grep -E 'MaxDirect|MaxHeapSize|NativeMemory|Use.*GC'
```
```
-XX:MaxDirectMemorySize=2147483648    ← 2 GiB
-XX:MaxHeapSize=132120576             ← 126 MiB，JVM 依 container limit 自動算出來的
-XX:NativeMemoryTracking=summary
-XX:+UseSerialGC                      ← 小記憶體、少 CPU 時 JVM 自動選 Serial GC
```
**解讀**：heap 上限 JVM 有照 container limit 自動調整，但 direct memory 上限被 `JAVA_TOOL_OPTIONS` 手動設成 2 GiB，**比 container limit 大 5 倍**。

### 步驟 4：用 NMT 找出在長的是哪一塊

```bash
oc exec $(pod lab4) -c app -- jcmd 1 VM.native_memory baseline
sleep 60
oc exec $(pod lab4) -c app -- jcmd 1 VM.native_memory summary.diff
```
```
Total: reserved=1759445KB +62451KB, committed=281969KB +62451KB
-                 Java Heap (reserved=129024KB, committed=11840KB)
-                     Class (reserved=1048840KB, committed=1736KB)
-                    Thread (reserved=15412KB, committed=1124KB)
-                      Code (reserved=248870KB +12KB, committed=8722KB +12KB)
-                        GC (reserved=424KB, committed=52KB)
-                  Internal (reserved=1259KB, committed=1259KB)
-                     Other (reserved=229398KB +62464KB, committed=229398KB +62464KB)
```
**解讀**
- 一分鐘內 `Total` 增加約 61 MiB，幾乎全部來自 `Other`（+62464KB）。
- 每分鐘約 60 MiB，也就是每秒約 1 MiB。log 每 30 秒增加 30 sessions，正好是 1 session/秒，**一個 session 吃 1 MiB**。
- `ByteBuffer.allocateDirect()` 的記憶體在 NMT 裡就算在 `Other`。
- 估算：384 MiB limit 扣掉 JVM 本身約 100 多 MiB，大約 4～5 分鐘就會撞到 limit，跟實際的 5.5 分鐘吻合。

### 步驟 5：找出誰持有這些 buffer

不開 heap dump 的快速方法：
```bash
oc exec $(pod lab4) -c app -- jcmd 1 GC.class_histogram | grep -E 'num|DirectByteBuffer|Cleaner'
```
```
 num     #instances         #bytes  class name (module)
  71:            37           2368  java.nio.DirectByteBuffer (java.base@25.0.4.1)
  87:            35           1400  jdk.internal.ref.Cleaner (java.base@25.0.4.1)
  98:            35           1120  java.nio.DirectByteBuffer$Deallocator (java.base@25.0.4.1)
```
**解讀**：37 個 `DirectByteBuffer` 在 heap 上一共只佔 **2368 bytes**，但每個都指向 1 MiB 的 native 記憶體。
這就是 heap 看起來很小、container 卻一直漲的原因。隔一分鐘再跑一次，數量會多 60 個左右。

要知道**是誰**持有這些物件，就需要 heap dump：
```bash
POD=$(pod lab4)
oc exec $POD -c app -- jcmd 1 GC.heap_dump /tmp/heap.hprof
```
```
Heap dump file created [9859972 bytes in 0.066 secs]
```
```bash
oc cp -c app $POD:/tmp/heap.hprof ./heap.hprof
```
```
executable file `tar` not found in $PATH: No such file or directory
command terminated with exit code 1
```
`oc cp` 其實是在容器裡執行 `tar`，hardened image 沒有 tar 就會失敗。改從有 tar 的 debug 容器，透過 `/proc/1/root` 拿：
```bash
kubectl debug $POD --image=$TOOLS --target=app --profile=restricted -c dbg -- sleep infinity
oc cp -c dbg $POD:/proc/1/root/tmp/heap.hprof ./heap.hprof
ls -la heap.hprof
```
```
tar: Removing leading `/' from member names
-rw-r--r--  1 you  staff  9859972 Nov 11 08:34 heap.hprof
```
用 Eclipse MAT 開啟 → Histogram → 找 `java.nio.DirectByteBuffer` → 右鍵 **Path to GC Roots**（exclude weak/soft references），
會看到它們都被 `App.SESSIONS`（一個 `ArrayList`）持有。這就是洩漏的源頭。

### 步驟 6（選用）：從 kernel log 拿被殺當下的實際用量

Pod status 只寫 `OOMKilled`。如果要證明被殺當下用了多少記憶體，例如要寫事故報告，就看節點的 kernel log：
```bash
oc debug node/<node> -q -- chroot /host bash -c 'journalctl -k --since "-30min" --no-pager | grep -E "Memory cgroup out of memory|oom-kill"'
```
```
kernel: oom-kill:constraint=CONSTRAINT_MEMCG,...,oom_memcg=/kubepods.slice/kubepods-burstable.slice/...
kernel: Memory cgroup out of memory: Killed process 208520 (java) total-vm:2946448kB, anon-rss:389968kB, file-rss:34432kB, shmem-rss:0kB, UID:1000840000 pgtables:1068kB oom_score_adj:976
```
- `CONSTRAINT_MEMCG`：是 **cgroup（container limit）** 觸發的 OOM，不是整台節點沒記憶體。
- `anon-rss:389968kB`：被殺的時候匿名記憶體約 381 MiB，差不多就是 384Mi 的 limit。
- `UID:1000840000`：restricted-v2 的 UID 範圍是依 namespace 分配的，可以拿來對應是哪個 namespace 的 Pod。

### 修正

1. **根本修正**：修掉洩漏，用完要釋放 session（要改程式）。
2. **讓 JVM 比 kernel 先發現**：把 direct memory 上限調到 limit 以內：
   ```bash
   oc set env deploy/lab4 JAVA_TOOL_OPTIONS="-XX:MaxDirectMemorySize=64m -XX:NativeMemoryTracking=summary"
   ```

### 驗證（以及一個陷阱）

調成 64m 之後大約 65 秒：
```bash
oc get pods -l app=lab4
oc logs deploy/lab4 | grep -v '^\s*at '
```
```
NAME                    READY   STATUS    RESTARTS   AGE
lab4-6854ff4487-j7mmm   1/1     Running   0          89s

01:26:39 active sessions: 30
01:27:09 active sessions: 60
Exception in thread "main" java.lang.OutOfMemoryError: Cannot reserve 1048576 bytes of direct buffer memory (allocated: 67108864, limit: 67108864)
```
**解讀**
- 好消息：現在 log 裡有 Java 的錯誤訊息，清楚說出是 direct buffer memory 用完了，不再是無聲無息被 kill。
- **陷阱**：Pod 還是 `1/1 Running`、`RESTARTS 0`。丟錯的是 main thread，它死了，但 HTTP server 的 thread 不是 daemon，
  所以 JVM 不會結束，`/` 也繼續回 `ok`。**應用其實已經壞了一半，Kubernetes 卻完全看不出來。**
- 你可能會想加 `-XX:+ExitOnOutOfMemoryError`。實測**沒有用**：這個 OOM 是 Java 程式碼（`java.nio.Bits.reserveMemory`）丟的，
  不是 JVM 內部丟的，所以那個旗標不會觸發。加了之後 Pod 一樣是 Running、不會重啟。
- 所以真正的保護要靠：修掉洩漏，加上一個**真的會檢查應用功能**的 liveness probe（不要只回固定的 `ok`）。

### 延伸重點
- 看到 OOMKilled，先比 **container 用量和 heap 用量**。差很多就是 native 記憶體的問題，heap dump 幫不上主要的忙，要靠 NMT。
- NMT 要在啟動時開（`-XX:NativeMemoryTracking=summary`），會有一些效能成本。正式環境可以只在除錯時用 `JAVA_TOOL_OPTIONS` 打開。
- 任何**手動設定的 JVM 記憶體上限**（`-Xmx`、`MaxDirectMemorySize`、`MaxMetaspaceSize`）都要跟 container limit 一起算。
  它們加起來超過 limit，就等於把錯誤處理交給了 kernel 的 SIGKILL。

---

## Lab 5：Service 的 targetPort 和實際監聽的 port 不一致

**一句話原因**：Service `backend` 把 9091 轉到 Pod 的 9091，但 backend 實際聽的是 9090。

**選用的工具**
| 步驟 | 工具 | 理由 |
|---|---|---|
| 重現連線 | `oc exec` + bash `/dev/tcp`、`getent` | 映像檔裡就有 bash，不需要 curl |
| 比對 Service、Endpoint | `oc get` | API 物件 |
| 確認 backend 實際聽的 port | `kubectl debug --target=app --image=$TOOLS` + `ss` | 需要 ss，不需要特權 |
| 抓封包 | `oc debug node` + `nsenter -n` + `tcpdump` | tcpdump 需要 NET_ADMIN，restricted-v2 不允許 ephemeral container 加 capability |

### 步驟 1：自己重現，確定失敗方式

```bash
oc logs deploy/lab5 | tail -2
oc exec deploy/lab5 -- getent hosts backend
oc exec deploy/lab5 -- bash -c 'time (exec 3<>/dev/tcp/backend/9091)'
```
```
01:29:24 ERROR: upstream unavailable
01:29:29 ERROR: upstream unavailable

172.30.121.247  backend.hi-debug-lab.svc.cluster.local

bash: connect: Connection refused
bash: line 1: /dev/tcp/backend/9091: Connection refused
real	0m0.004s
```
**解讀**
- `getent` 解析得到 Service IP，所以 DNS 沒問題。
- 4 毫秒就被拒絕，是 refused 不是 timeout。網路是通的，是那個 port 沒有人在聽。

### 步驟 2：沿著鏈逐段比對 port

```bash
oc get svc backend -o jsonpath='{.spec.ports}'; echo
oc get endpoints backend
oc get pod $(pod backend) -o jsonpath='{.spec.containers[0].ports}'; echo
```
```
[{"port":9091,"protocol":"TCP","targetPort":9091}]
NAME      ENDPOINTS           AGE
backend   10.128.0.133:9091   10m
[{"containerPort":9090,"protocol":"TCP"}]
```
確認 backend **實際**在聽什麼：
```bash
kubectl debug -it $(pod backend) --image=$TOOLS --target=app --profile=restricted -c dbg -- ss -tlnp
```
```
State  Recv-Q Send-Q Local Address:Port Peer Address:PortProcess
LISTEN 0      0                  *:9090            *:*    users:(("java",pid=1,fd=5))
```
**解讀**
| 段落 | 值 |
|---|---|
| frontend 連的 | `backend:9091` |
| Service port → targetPort | 9091 → **9091** |
| Endpoint | `10.128.0.133:9091` |
| backend 實際監聽 | **9090** |

斷在 targetPort 這一段。注意：`containerPort: 9090` 宣告是對的，但 Service 的 targetPort 寫成數字 9091，
所以根本沒有參考 containerPort。`containerPort` 只是宣告用，不影響流量。

也可以直接連 Pod IP 驗證：
```bash
oc exec deploy/lab5 -- bash -c 'exec 3<>/dev/tcp/10.128.0.133/9090; printf "GET /ping HTTP/1.0\r\n\r\n" >&3; cat <&3' | tail -1
```
```
pong
```

### 步驟 3：用封包證明

這題其實到步驟 2 就找到原因了。抓封包是要練習**連線失敗、又沒有權限時，怎麼拿到封包層的證據**。

先試試從 Pod 層抓，會被 SCC 擋下來：
```bash
kubectl debug $(pod lab5) --image=$TOOLS --target=app --profile=netadmin -c net -- sleep 600
```
```
Error from server (Forbidden): pods "lab5-76c85f94fd-t82rz" is forbidden: unable to validate against any security context constraint:
provider restricted-v2: .ephemeralContainers[0].capabilities.add: Invalid value: "NET_ADMIN": capability may not be added
```
ephemeral container 也要通過 Pod 的 SCC 檢查。restricted-v2 不允許加 capability，所以只能改從節點抓：
```bash
oc debug node/$(oc get nodes -o name | head -1 | cut -d/ -f2)
```
在 node debug pod 裡：
```bash
POD=$(chroot /host crictl pods --namespace hi-debug-lab --label app=lab5 --state ready -q | head -1)
CID=$(chroot /host crictl ps --pod $POD --name app -q | head -1)
PID=$(chroot /host crictl inspect --output go-template --template '{{.info.pid}}' $CID)
echo "POD=$POD CID=$CID PID=$PID"
nsenter -t $PID -n -- tcpdump -nn -i eth0 -c 6 tcp
```
```
POD=8c91126ffbe1... CID=98c471ea1513... PID=128110
10.128.0.109.45964 > 172.30.135.94.9091: Flags [S], seq 484147133, ...
172.30.135.94.9091 > 10.128.0.109.45964: Flags [R.], seq 0, ack 484147134, win 0, length 0
10.128.0.109.45968 > 172.30.135.94.9091: Flags [S], seq 1803709677, ...
172.30.135.94.9091 > 10.128.0.109.45968: Flags [R.], seq 0, ack 1803709678, win 0, length 0
```
**解讀**
- 每一組都是 `[S]`（SYN）之後**馬上**收到 `[R.]`（RST+ACK）。
- 封包裡的對象是 Service IP，因為 OVN 是在 Pod 外面才做 DNAT，所以在 Pod 裡看到的一直是 Service IP。
- 不同失敗情況的封包長相：
  | 情況 | tcpdump 會看到 |
  |---|---|
  | port 沒人聽 | `[S]` → `[R.]` |
  | 被丟棄（NetworkPolicy、路由） | `[S]` 一直重送，沒有回應 |
  | 正常 | `[S]` → `[S.]` → `[.]` 三向交握 |
  | DNS 失敗 | 根本沒有 SYN，只有 port 53 的查詢 |

**為什麼要這樣組合**：`crictl` 在主機上，所以要 `chroot /host`；`tcpdump` 在 debug pod 的映像檔裡，主機（RHCOS）上沒有。
所以先用 chroot 拿到 PID，再從 debug pod 用 `nsenter -n` **只進入**目標的 network namespace，工具還是用 debug pod 自己的。

### 修正

```bash
oc patch svc backend --type=json -p '[{"op":"replace","path":"/spec/ports/0/targetPort","value":9090}]'
oc get endpoints backend
```
```
service/backend patched
NAME      ENDPOINTS           AGE
backend   10.128.0.133:9090   10m
```

### 驗證

```bash
oc logs deploy/lab5 | tail -1
```
```
01:29:54 upstream ok: pong
```
frontend 不用改，也不用重啟。它連的 9091 是 Service 的 port，這部分本來就沒錯。

### 延伸重點
- 比較好的寫法是在 Pod 上幫 port 取名字（`ports: [{name: http, containerPort: 9090}]`），
  然後 Service 用 `targetPort: http`。這樣改 port 時只要改一個地方。
- `oc debug node` + `crictl` + `nsenter` 是 **SCC 不允許、但你又一定要用特權工具**時的標準做法。
  需要 cluster-admin，而且等於擁有整台節點的權限，用完要記得退出（debug pod 會自動刪除）。
- 如果懷疑是 NetworkPolicy，要用**跟 lab5 相同 label** 的 Pod 測試，因為 NetworkPolicy 是依 label 套用規則的：
  `oc debug deploy/lab5 --keep-annotations --keep-labels --image=$TOOLS -- curl -sv http://backend:9091/ping`。
  注意 `--keep-labels` 也會讓副本符合 Service 的 selector，對有 Service 的 Deployment 用的話，副本會開始接到真正的流量。

---

## oc debug vs kubectl debug

> 以下是測試叢集上實測的結果：本機 `oc` 4.14、`kubectl` 1.27.4，叢集 OCP 4.18（Kubernetes 1.31）。
> 新版 client 的選項可能更多（例如較新的 kubectl 才有 `sysadmin` profile），以 `--help` 為準。

### 五種模式

| 指令 | 建立什麼 | 跟原本的 Pod 的關係 |
|---|---|---|
| `kubectl debug <pod> --target=app` | ephemeral container | **加進原本的 Pod**，共用 PID 和網路 |
| `kubectl debug <pod> --copy-to=<new>` | 新 Pod（副本），app 照原本的指令跑 | 不碰原本的 Pod |
| `kubectl debug node/<node>` | 節點上的 Pod（非特權） | 不碰原本的 Pod |
| `oc debug deploy/<name>` | 新 Pod（副本），command 換成 shell | 不碰原本的 Pod |
| `oc debug node/<node>` | 節點上的特權 Pod（root） | 不碰原本的 Pod，但可以用 nsenter 進到原本的行程 |

### 功能對照

| | `kubectl debug <pod> --target` | `kubectl debug --copy-to` | `oc debug deploy/x` | `oc debug node/x` | `kubectl debug node/x` |
|---|---|---|---|---|---|
| 做了什麼 | 在**原本的 Pod** 加一個 ephemeral container | 複製一個新 Pod，再加一個 debug 容器 | 複製一個新 Pod，把 command 換成 shell | 在節點上起特權 Pod | 在節點上起 Pod |
| 會不會改到原本的 Pod | **會**（改 Pod spec，加了就移除不掉） | 不會 | 不會 | 不會 | 不會 |
| 看得到原本的行程 | ✅ 共用 PID namespace | ❌ 只看得到副本裡的行程 | ❌ | ✅ 用主機 PID + nsenter | ⚠️ 看得到 PID，但權限不夠 |
| 副本裡的 app | — | **照原本的指令跑**（lab1 一樣會 crash） | **不跑**，換成 shell 或你指定的指令 | — | — |
| 映像檔 | 必須用 `--image` 指定 | 加一個 `--image` 的容器（或用 `--set-image` 換掉原本的） | 預設用**同一個映像檔**，`--image` 可換 | OpenShift tools 映像檔（有 tcpdump） | 必須指定 |
| env、volume | 不共用（ephemeral 有自己的 env） | 原容器保留 | ✅ 保留，換映像檔也保留 | — | — |
| label | 不變（是同一個 Pod） | 拿掉 | 拿掉（`--keep-labels` 保留） | — | — |
| annotation／SCC | 依原 Pod 的 SCC 驗證（NET_ADMIN 會被拒絕） | 保留，實測拿到 `restricted-v2` | 拿掉（`--keep-annotations` 保留）。能用 `anyuid` 的使用者（例如 cluster-admin）預設會變成 `anyuid` | `privileged`、root | 實測拿到 `hostaccess`，**非 root**，`chroot /host` 失敗 |
| probe | 不變 | — | 預設拿掉（`--keep-liveness` / `--keep-readiness`） | — | — |
| 需要映像檔有 shell | 不需要 | 不需要 | **預設需要**（不然要用 `--image` 或指定指令） | 不需要 | 不需要 |
| 結束後清理 | 移除不掉，要等 Pod 重建 | **不會自動刪**，要自己 `oc delete pod` | 自動刪除（`--preserve-pod` 保留） | 自動刪除 | **不會自動刪** |
| 先預覽會建立什麼 | ❌ | ❌ | ✅ `-o yaml` | ✅ `-o yaml` | ❌ |
| 覆寫 app 的環境變數 | ❌（`--env` 只設定 debug 容器自己） | ❌（`--env` 只設定 debug 容器自己） | ✅ `oc debug deploy/x KEY=VALUE` | — | — |
| 其他實用選項 | `--profile` | `--set-image`（換掉 app 的映像檔）、`--same-node`（排到同一個節點）、`--replace`（⚠️ **會刪掉原本的 Pod**） | `--as-user`、`--one-container`、`--to-namespace` | `-q`（不印提示，方便串流輸出） | `--profile` |
| 需要的權限 | `pods/ephemeralcontainers` | 建立 Pod | 建立 Pod | cluster-admin | 建立 Pod + hostaccess 等 SCC |

### 實測到的差異

**1. 本機 `oc` 4.14 不能開 ephemeral container**

`oc debug` 沒有 `--target`。要共用原本 Pod 的 PID namespace，只能用 `kubectl debug`（或換新版的 oc）。

**2. `oc debug` 預設會換掉 SCC**
```
oc debug deploy/lab1 -- id                      → uid=65532       (anyuid)
oc debug deploy/lab1 --keep-annotations -- id   → uid=1000860000  (restricted-v2)
kubectl debug <pod> --copy-to=... (實測)        → restricted-v2
```
`oc debug` 用你的身分建 Pod，又拿掉了 `openshift.io/required-scc`，所以 SCC admission 會挑你能用的、優先序最高的 SCC。
在正式環境，這代表**副本的權限可能比原本的 Pod 大**，權限類的問題就會「在副本裡重現不出來」。

**3. 節點除錯：`oc debug node` 能用，`kubectl debug node` 實際上不能用**
```
kubectl debug node/<node> --image=$TOOLS -- bash -c 'id; chroot /host crictl ps'
  Warning: would violate PodSecurity "baseline:latest": host namespaces (hostNetwork=true, hostPID=true, hostIPC=true), hostPath volumes ...
  uid=1000860000(1000860000) ...
  chroot: cannot change root directory to '/host': Operation not permitted

kubectl debug node/<node> --profile=sysadmin ...
  error: unknown profile: sysadmin          ← kubectl 1.27 沒有這個 profile
```
kubectl 建的節點 Pod 沒有要求 privileged，所以 SCC 只給了 `hostaccess`，而且是非 root。
`oc debug node` 會明確要求 `privileged: true`、`runAsUser: 0`，再加上 OpenShift 的 tools 映像檔。在 OpenShift 上做節點除錯，用 `oc debug node` 就對了。

**4. `kubectl debug --copy-to` 的副本裡，app 會照原本的指令跑**
```
kubectl debug <lab1 pod> --copy-to=lab1-copy --share-processes --image=$TOOLS -c dbg -- sleep 600
oc get pod lab1-copy
  NAME        READY   STATUS   RESTARTS      AGE
  lab1-copy   1/2     Error    1 (19s ago)   26s
```
副本裡的 app 照樣 crash，適合在 app 啟動的瞬間用 `--share-processes` 的 debug 容器 `strace` 它。
如果想要 app **不要跑**、只要環境，就用 `oc debug`。
用完要記得 `oc delete pod lab1-copy`，它不會自己消失。

### 什麼時候用哪一個

| 我想要… | 用這個 |
|---|---|
| 看**正在跑的**行程：socket、thread dump、`/proc/1/root` | `kubectl debug <pod> --target=app --image=...` |
| 一樣看正在跑的行程，但 SCC 不允許我要的權限（NET_ADMIN、root） | `oc debug node/<node>` + `nsenter` |
| 不想（或不能）改到原本的 Pod | `oc debug deploy/x`，或 `oc debug node` |
| CrashLoopBackOff：要一個**不會 crash** 的環境慢慢看 | `oc debug deploy/x --keep-annotations` |
| CrashLoopBackOff：要在 app **啟動的瞬間**觀察它 | `kubectl debug --copy-to --share-processes` |
| 不改 Deployment，先試修正（換 env） | `oc debug deploy/x --keep-annotations KEY=VALUE -- <啟動指令>` |
| 用跟應用相同的身分測網路、NetworkPolicy | `oc debug deploy/x --keep-annotations --keep-labels --image=$TOOLS` |
| 抓封包、看 kernel log、看 cgroup | `oc debug node/<node>` |
| 把大檔案拿出來，又沒有 tar | `oc debug node/<node> -q -- cat /proc/<PID>/root/<path> > file` |

**記一句話就好**：要看**活著的那個行程**，就用 ephemeral container 或節點層；要一個**一樣的環境**，就用 `oc debug` 副本。

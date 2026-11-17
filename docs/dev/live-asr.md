# Fun-ASR实时语音识别：自建服务与阿里云百炼

## 入口与配置

设置 › 模型 › 语音转文字里「边说边转的语音服务」一组，豆包下面一行「Fun-ASR实时语音识别」。它的设置页（`app/ui/LiveSpeechSetup.kt`）顶上三个分页并排：「自建」「百炼·北京」「百炼·新加坡」，各管各的设置，打开时停在正在用（或上次用）的那个（`FunAsrTab.of`）。没设置过时点这一行进设置页；设置过以后点圆圈就沿用上次那种（`SpeechSettings.lastLive`：自建的地址，或百炼某个地域的模型和业务空间；那种用不了了就进设置页），右边「设置」进去改。行上第二行写正在用的是哪种，如「自建 · 192.168.1.10:10095」「百炼·北京 · fun-asr-realtime」。

- **自建**：填服务地址。只写「主机」或「主机:端口」时按 `ws://` 补全，没写端口补 10095（Fun-ASR 仓库里 serve_realtime_ws.py 的默认端口）；`http(s)://` 换成 `ws(s)://`，带账号密码的地址不收（`FunAsrProtocol.normalize`）。地址不是密钥，存在选择 JSON 里（`SpeechChoice.FunAsr`），另记一份（`SpeechSettings.funAsrUrl`），换去别的再回来时预填。
- **百炼·北京 / 百炼·新加坡**：北京和新加坡的 API Key 不通用，每个地域各存一份 Key、上次的模型和业务空间 ID。Key 用 `KeyCipher` 加密单独存（和豆包凭据同一个 Keystore alias、不同的键），不回填；已存时留空沿用，只换模型或业务空间。模型列出 `fun-asr-realtime`（默认）、`paraformer-realtime-v2`、`qwen-audio-3.1-asr-flash-message`，也能手填同一套 run-task 协议的别的模型（只收字母、数字和 `.-_`）。业务空间 ID 可选：填了连它的专属接入点 `wss://<业务空间>.<地域>.maas.aliyuncs.com/api-ws/v1/inference`（地域段是 `cn-beijing` 或 `ap-southeast-1`，文档推荐），不填连通用接入点 `dashscope.aliyuncs.com` / `dashscope-intl.aliyuncs.com`（仍可用）；ID 去掉空白转小写，没带 `llm-` 前缀时补上（控制台里的 ID 以它开头），只收字母、数字和连字符（拼进主机名，`BailianProtocol.workspace`），页上写出这次会连的地址。选择 JSON 只有模型、地域和业务空间 ID（`SpeechChoice.Bailian`）。删除某个地域的 Key 时，正在用这个地域就关掉语音输入。

三个分页都有「识别语言」一组（`AsrLanguage`）：自动（默认，不指定，由服务自己判断）、中文、英语、日语、粤语、韩语，各分页各记一份，存进选择 JSON 的 `language`（不认识的代码当自动，不让整条选择失效）。自建服务能识别哪些语言取决于它跑的模型（Fun-ASR-Nano 只有中、英、日及中文方言，多语种版本和 Qwen3-ASR 更多）。

三个分页的「连接并使用」都先试连一次（`probeLiveAsr`）：连上、发开场消息，服务端回了「开始」（地址、鉴权、模型都认）就算通，随后发收尾消息断开，才存下并选用。试连不送音频，不产生识别时长；不等服务端对空识别的收尾回应。没通时页上写原因（`SpeechOptions.probeFailure`：Key 被拒、服务拒绝了请求（只带错误码）、连不上），不显示服务端原话。

## 实时路径

`SpeechChoice.FunAsr` / `SpeechChoice.Bailian` → `SpeechResolver` → `VoiceInput`（百炼按地域取 Key、按业务空间定接入点）→ `LiveSpeech`（`app/voice/LiveAsr.kt`）。对话页输入框和悬浮胶囊共用同一条入口，松开后的去向和别的引擎一样：输入框里填进输入框由用户发出，胶囊里直接发给它对着的那件事。所以交出的 `Final` 必须是服务端说完的全文，不能是没收完的中间结果。

- 两家都是「文字控制消息 + 二进制 PCM 音频」：`LiveAsrProtocol` 只管开场发什么、收尾发什么、怎么读服务端的消息（读成 `Started` / `Text(stable, tail)` / `Ended` / `None`，服务端报错抛 `LiveAsrError`，只带错误码）；连接、上传、等终结果都在 `LiveSpeech`。
- `LiveAsrTransport` 是独立的 OkHttp WebSocket 客户端：不跟随重定向、读不设超时，二进制回应按 UTF-8 文字读；握手 401/403 报 `ModelError.Auth`、429 报限流、其余算网络不通，不带出服务端原话。请求头只有给它的那些（百炼是 `Authorization: Bearer <Key>`，自建服务没有）。
- `PcmRecorder(streaming = true)` 给 16 kHz 单声道 PCM16，50 ms 一块，合成 100 ms 一包（小端）。服务端回「开始」前不送音频，这期间的录音留在录音器的队列里（最多约 5 秒）。百炼两包之间至少隔 50 ms（官方示例按实时 100 ms 一包，连上前攒下的音频按两倍速追上）；自建服务不限速。
- 每份 `Text` 都是整份替换，不拼接；`stable` 是定下来的句子，`tail` 是还在变的那句，界面照 `Partial(stable, tail)` 画。拼句子时前后都是拉丁字母或数字才补空格（`joinAsr`）。
- 松开时停录音，送完剩下的音频（不足一包的尾巴照送）再发收尾消息，等服务端说结束才发 `Final`；断开、出错或终结果超时（10 秒）都算失败，不把中间结果当最终文字。握手和开场各等 10 秒；整次会话不设上限（`SpeechSession.unlimited`）：按住多久都行，不受按住说话的 60 秒封顶，也不倒数，录音器边录边送、不留整段录音（`PcmRecorder` 的 `unbounded`）。字太多时卡片上去掉前面说完的句子，最新的字一直看得见。自建服务的终结果是服务端把最后一段（上次停顿以后的）整段重解，服务端单次最多输出 512 个 token，一口气不停顿说很久时最后这段可能被截短；中间有约 3 秒停顿就会切句，不受影响。取消时关掉事件、录音和连接，迟到的结果不再冒出来。

### 自建 Fun-ASR 的消息（`FunAsrProtocol`）

文字 `START` → 服务端回 `{"event":"started"}`；之后按住时送音频，服务端约每半秒回一份到此刻的全文 `{"sentences":[{"text","start","end"}],"partial":"…","partial_start_ms","duration_ms","is_final":false}`（`sentences` 是 VAD 切出的已定句子，`partial` 是正在说的那句，每份都是整份）；文字 `STOP` → 先回 `is_final` 为 true 的终结果（这时 `partial` 并进 `sentences`），再回 `{"event":"stopped"}`，收到 `stopped` 才结束。没送过音频时服务端不给终结果、直接回 `stopped`，当没听清（空串）。一段话说得长、中间没有停顿（VAD 还没切句）时，服务端只拿最近 15 秒（服务端参数 `--partial-window-sec`）去解 `partial`，前面的字会从 `partial` 里掉出去，要等这段说完才整段放进 `sentences`；所以 `partial_start_ms` 变大而 `sentences` 没变时，把一份份 `partial` 接成到此刻的全文（`PartialStitch`）：先找新的一份开头落在已显示的字里的哪个位置（只比字母和数字，拿开头 12 个字逐个位置对，对上最多的算数，允许个别字被改写；对上的不到一半或少于 3 个，按时间比例估）；重叠那截的开头 8 个字沿用已显示的——新的一份开头是窗口最前面、前文被截掉，最容易解错或解重——往后到两份连着两个字一样的地方才换成新的（不在英文单词中间换），找不到一样的就从窗口开头整份换。服务端把这段放进 `sentences` 时丢掉留住的字、改用服务端的句子。这只影响按着时的预览，最终文字只用服务端的终结果。指定识别语言时，`START` 之前先发 `LANGUAGE:<中文语言名>`（中文、英文、日文、粤语、韩文；服务端把它拼进提示词「语音转写成…」），服务端回 `language_set`；自动时不发。`hotwords_set`、`language_set` 等确认不改文字。每次按住说话用一个新连接。服务端的模型串行解码，多人同时用会排队。

### 自建服务换别的模型（Qwen3-ASR + vLLM）

「自建」这一栏认的是上面这套消息，不是某个模型：任何照这套消息收发的服务都能填进来。**模型识别**：服务端可以在 `started` 里多带一项 `model`（模型 id，原协议没有），试连时记进选择里（`SpeechChoice.FunAsr` 的 `model`，只收字母、数字和 `._/-`，`FunAsrProtocol.cleanModel`）；没带的按 Fun-ASR 的默认模型 `FunAudioLLM/Fun-ASR-Nano-2512` 算，带 `Qwen/Qwen3-ASR-1.7B` 这类 id 的认作 Qwen3-ASR，别的 id 直接显示短名（`FunAsrProtocol.family`）。识别结果只用来显示——模型页那一行写「自建 · 主机:端口 · 模型短名」，卡片和按住说话的小标签写「Qwen3-ASR（自建）」或「Fun-ASR（自建）」——不因此改变发什么；换了后端要重新「连接并使用」一次才会更新。

Qwen3-ASR 没有现成的同协议服务，两个现成接口都不适合直接接（同一批录音实测）：

- **vLLM 自带的实时接口**（/v1/realtime，启动时把模型结构换成 Qwen3ASRRealtimeGeneration）：固定 5 秒一段、各段单独解、不带上文、不能指定语言。每 5 秒才出一次字，接缝处断字、重字、整段重出，真人朗读错三成左右。在 vLLM 0.25.1 上实测；2026-11-18 对过主干的源码，分段和解码的做法没有变。
- **官方包 qwen-asr 的流式接口**（init_streaming_state / streaming_transcribe / finish_streaming_transcribe）：是进程内的 Python 接口，没有 WebSocket，也不断句。外面套一层薄转换就能接上这套消息、终结果也准，但音频只增不减，一次会话说上几分钟、超过模型的上下文长度就报错（上下文设 4096 时一份 5 分钟的录音没能跑完）；指定语言时静音会被解成语气词；默认 2 秒一份预览；包（0.0.6）把 vLLM 锁在 0.14.0。

推荐的做法是模型交给 vLLM 托管，前面放一层说这套消息的前端，用官方流式接口的同一种解法、再加上断句（下面的参数在 vLLM 0.25.1 上跑过）：

```sh
vllm serve Qwen/Qwen3-ASR-1.7B --host 127.0.0.1 --port 10098 \
  --max-model-len 4096 --gpu-memory-utilization 0.2 --chat-template chat_template.jinja
```

`chat_template.jinja` 是模型自带的对话模板（权重目录里 chat_template.json 的内容）改两处：收集 system 文字的那个循环里顺带把 assistant 消息的文字攒起来，结尾按有没有 assistant 消息决定怎么开回复——

```jinja
{%- elif m.role == "assistant" -%}
  {%- set ns.has_prefix = true -%}
  {%- set ns.prefix = ns.prefix + m.content -%}
{# …system、user 两段照模型自带的写… #}
{%- if ns.has_prefix -%}
{{- "<|im_start|>assistant\n" + ns.prefix -}}
{%- elif add_generation_prompt -%}
{{- "<|im_start|>assistant\n" -}}
{%- endif -%}
```

请求体里带上 `"add_generation_prompt": false, "continue_final_message": true`，最后一条消息放 assistant 的开头文字，返回的就只有接在它后面的字。

- **托管**：不加 `--max-model-len` 时默认 65536，显存配额小就起不来；4096 够放约 5 分钟的音频（每秒约 13 个 token），一段话最长按 60 秒算绰绰有余。权重约占 4 GB 显存，其余是并发请求用的缓存，配额按显卡大小定。只监听本机，对外的是前端。
- **接口**：用对话补全接口（/v1/chat/completions），音频整段放进一条 user 消息；转写接口（/v1/audio/transcriptions）会把音频按 30 秒切开再拼，拼接处多出标点，不用它。回复以什么开头靠上面改过的对话模板指定。
- **断句**：流式 VAD 切出语音段；一段说完（停顿、收到 `STOP`，或 60 秒没有停顿）才把它放进 `sentences`。
- **预览**（约每秒一份 `partial`）：把这一段到此刻的音频整段重送，上一份结果去掉末尾约 5 个 token 后作为回复开头，模型只往后续写；每段的头两份不带开头。前面的字不会来回变。`partial_start_ms` 一直是这段的起点，不要只送最近一截——那样前面的字只能靠客户端接（`PartialStitch`）。
- **定稿**：段结束后不带开头、整段重解一次，用这次的结果。
- **语言**：模型的回复以「language 语言名」加 `<asr_text>` 标记开头，要去掉；语言名是 None 表示没听到话，当空。先让模型自己判断，它判断的和 `LANGUAGE:` 指定的不一样才把语言标记放进回复开头重解。`HOTWORDS:` 的词放进 system 消息。
- **别截口吃**：「因为因为因为」是说话人真这么说的，照实留下；只有同一小段连出几十遍才当模型在打转。
- **其余照旧**：`START` 回 `started`（带上 `model`）、可重复开始；`LANGUAGE:` 和 `HOTWORDS:` 在 `START` 前后都可能来；没送过音频的 `STOP` 只回 `stopped`；模型服务不通时直接断开连接，别让客户端等到超时；整份文件快速灌进来时（比实时快一倍以上）不必出预览。

同一批录音、同一个客户端对比过（清晰朗读、带口头语的朗读、多人闲聊各几段，30 到 50 秒）：两种模型的终结果在清晰朗读上都没有错字；换成上面这种前端后，预览比音频晚 0.1 秒上下（原来 0.3 秒上下），预览改写前文的次数从每段最多十几次降到最多一次。

### 百炼的消息（`BailianProtocol`）

连上后发 `run-task`（`header.action/task_id/streaming=duplex`，`payload.task_group=audio, task=asr, function=recognition, model, parameters{format=pcm, sample_rate=16000}, input{}`，task_id 每次新生成 32 位十六进制），等 `task-started` 再送音频。`result-generated` 里 `payload.output.sentence` 按 `sentence_id` 是同一句的整份替换，`sentence_end` 为真时这句定下，定下后迟到的同号中间结果不再改它；`heartbeat` 为真的跳过。送完音频发 `finish-task`（同一个 task_id），等 `task-finished` 才结束——不等就会丢掉最后一句。`task-failed` 只取 `error_code`，鉴权、欠费一类（InvalidApiKey、AccessDenied、Arrearage 等）报成 Key 被拒。通义听写消息版（`qwen-audio-*`）默认不给中间结果，开场时为它加 `intermediate_result_enabled=true`，这是唯一按模型名区分的地方（不加就不能边说边出字）。指定识别语言时 `parameters.language_hints` 只给一个语言代码（zh、en、ja、yue、ko；fun-asr-realtime 只认第一个），自动时不传。

## 验证与限制

`FunAsrProtocolTest`、`PartialStitchTest`、`BailianProtocolTest`、`LiveSpeechTest`、`LiveAsrTransportTest`、`LiveSpeechSettingsTest` 覆盖：两家的消息样例（自建服务的样例取自真实服务的回应，含一段 40 多秒不停顿的话的全部回应 `app/src/test/resources/voice/funasr-long-utterance.jsonl`：预览去掉标点后每一份都是终结果的开头）、地址规范化、确认前不送音频、整份替换、尾包、只在结束事件后给 `Final`、断开和超时失败、取消、试连、请求头、握手被拒不带原话、Key 加密存取与删除、选择读写与坏数据退回自动、自建服务报的模型 id 的识别与存取、设置页的说法。WebSocket 测试只连本机 MockWebServer。

```sh
./gradlew :app:testDebugUnitTest --tests 'com.wangzan101.mika.voice.*'
```

真机上验证过：自建（局域网里的 Fun-ASR-Nano 实时服务）按住说话边说边出字、松开后文字进输入框；百炼北京填 Key 和业务空间 ID 后试连通过，按住说话同样出字。百炼的收费、延迟和准确率以账号实际为准。

## 资料

2026-11-07 核对：

- [Fun-ASR 仓库](https://github.com/FunAudioLLM/Fun-ASR)（serve_realtime_ws.py、docs/realtime_demo.md）
- [Qwen3-ASR 仓库](https://github.com/QwenLM/Qwen3-ASR)（流式示例 examples/example_qwen3_asr_vllm_streaming.py；2026-11-18 核对）
- [百炼实时语音识别](https://help.aliyun.com/zh/model-studio/real-time-speech-recognition-user-guide)、[Fun-ASR 实时语音识别 WebSocket API](https://help.aliyun.com/zh/model-studio/fun-asr-realtime-websocket-api)

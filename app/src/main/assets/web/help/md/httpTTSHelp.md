# 在线朗读规则说明

* 在线朗读规则为url规则,同书源url
* js参数
```
speakText //朗读文本
speakSpeed //朗读速度,5-50
```
* 例:
```
http://tts.baidu.com/text2audio,{
    "method": "POST",
    "body": "tex={{java.encodeURI(java.encodeURI(speakText))}}&spd={{String((speakSpeed + 5) / 10 + 4)}}&per=5003&cuid=baidu_speech_demo&idx=1&cod=2&lan=zh&ctp=1&pdt=1&vol=5&pit=5&_res_tag_=audio"
}
```

## 内置 WebSocket TTS

火山引擎语音合成大模型 V3 双向流式接口已内置专用客户端。在线朗读引擎的 `url` 填:

```
wss://openspeech.bytedance.com/api/v3/tts/bidirection
```

登录配置支持以下字段:

* `合成API Key`: 火山语音合成大模型 WebSocket 接口的 API Key,会作为 `X-Api-Key` 发送
* `OpenAPI AK` + `OpenAPI SK`: 火山控制台的 Access Key ID / Secret Access Key,用于刷新公版音色列表；不要填合成 API Key
* `Resource ID`: 默认 `seed-tts-2.0`
* `刷新公版音色列表`: 使用 `OpenAPI AK` + `OpenAPI SK` 调用 `ListSpeakers`,拉取后 `Speaker` 会变成下拉列表
* `Speaker`: 音色/声音复刻/混音音色 ID；下拉项格式为 `音色名 性别 | VoiceType`,合成时会自动取 `|` 后面的 VoiceType
* `Model`: 可选模型 ID
* `Format`: 默认 `mp3`,当前朗读播放管线建议使用 `mp3`
* `Sample Rate`: 默认 `24000`
* `Speech Rate`: 可选,不填时跟随朗读语速
* `Audio Params Extra`: 追加到 `req_params.audio_params` 的 JSON
* `Additions Extra`: 追加到 `req_params.additions` 的 JSON
* `Raw Req Params`: 完全自定义 `req_params` 的 JSON

后续接入其他 WebSocket/大模型 TTS 时,可在 `OnlineTtsClient` 中新增客户端,再按 URL 分发。

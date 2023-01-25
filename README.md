# ProtPushProxy

SNSサーバからWebPushを受け取りアプリに中継するプロトタイプ。

- OpenAPIでAPIを定義してktorで実装
- UnifiedPush対応
- 1アクセストークンで複数デバイスに中継する


# 構成

メッセージ配送:
```
SNSサーバ
↓
アプリサーバ
↓
UPディストリビュータのサーバ
↓
(APP) UPディストリビュータのレシーバ
↓
(APP) UPのMessagingReceiver
↓
アプリ固有の処理
```

登録：
```
アプリ
↓
try{
	UnifiedPush.getDistributors(context)
		.find{...}
		?.let{ 	UnifiedPush.saveDistributor(context, selectedDistoributer) }
		UnifiedPush.registerApp(context,instance, features, messageForDistributor)
}catch(…){
	…
}
↓(非同期)
MessagingReceiver.onNewEndpoint, onRegistrationFailed が呼ばれる
↓
アプリはendpointをアプリサーバに送る
```

登録解除：
```
try{
	UnifiedPush.unregisterApp(context,instance)
}catch(…){
	…
}
↓(非同期)
MessagingReceiver.onUnregistered が呼ばれる
↓
アプリは登録解除をアプリサーバに伝える
```

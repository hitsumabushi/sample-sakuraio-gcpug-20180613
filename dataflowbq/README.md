# Dataflow サンプル

## 概要

```
sakura.io ---> GCP Pub/Sub ---> Dataflow ---> BigQuery
```

sakura.io からのメッセージは、以下のようなJSON。

```json
{"datetime": "2018-06-05T20:00:39.734012233Z", "id": "321513702036165632", "module": "uQvfXqBm7fQC", "payload": {"channels": [{"channel": 0, "datetime": "2018-06-05T20:00:38.785014586Z", "type": "I", "value": 70}, {"channel": 1, "datetime": "2018-06-05T20:00:38.924014586Z", "type": "I", "value": 4156537390}, {"channel": 2, "datetime": "2018-06-05T20:00:39.063014586Z", "type": "f", "value": 3133.9}, {"channel": 3, "datetime": "2018-06-05T20:00:39.202014586Z", "type": "f", "value": 19.4375}, {"channel": 4, "datetime": "2018-06-05T20:00:39.341014586Z", "type": "f", "value": -113}, {"channel": 5, "datetime": "2018-06-05T20:00:39.480014586Z", "type": "f", "value": 3.1638427}]}, "type": "channels"}
```

| チャンネル            | 内容              |
| ---------------------:| ----------------- |
| 0                     | カウンタ          |
| 1                     | シリアル番号      |
| 2                     | 距離センサー値    |
| 3                     | 温度センサー値    |
| 4                     | 現状不要な値      |
| 5                     | 現状不要な値      |


## deploy

### setup BigQuery

1. Dataset の作成: `demo_gcpug_20180613`
2. テーブルの作成: 次のSQLを参考に作成

```sql
; サンプルデータを利用する場合: 分割テーブルを利用できない
#standardSQL
CREATE TABLE demo_gcpug_20180613.sample (datetime TIMESTAMP, module STRING, ch0 INT64, ch1 STRING, ch2 FLOAT64, ch3 FLOAT64, ch4 FLOAT64, ch5 FLOAT64)
OPTIONS(
  description="a table partitioned by datetime"
)

; リアルタイムなデータを利用する場合: 分割テーブルを使ってテーブルを作成
#standardSQL
CREATE TABLE demo_gcpug_20180613.sample (datetime TIMESTAMP, module STRING, ch0 INT64, ch1 STRING, ch2 FLOAT64, ch3 FLOAT64, ch4 FLOAT64, ch5 FLOAT64)
PARTITION BY DATE(datetime)
OPTIONS(
  description="a table partitioned by datetime"
)
```


### setup storage

1. バケットの作成
2. バケット内に、 `tmp` ディレクトリ を作成

### Change pom.xml

pom.xml 内の `<properties>` を書き換える。

| 項目                  | 内容                         | 例                                      |
| --------------------- | ---------------------------- | --------------------------------------- |
| target.project        | GCPプロジェクト名            | sample-project                          |
| target.subscription   | Pub/Sub サブスクリプション名 | demo-gcpug                              |
| target.bigquery.table | BigQuery のテーブル          | sample-project:demo-gcpug-detaset.table |
| target.templocation   | 一時ファイルの場所           | gs://demo-gcpug-dir/tmp                 |
| target.jobname        | Dataflow のジョブ名          | sample-job                              |



### deploy to dataflow

```sh
$ mvn compile exec:java
```

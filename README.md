# socket-log-receiver

## Compile

```shell
mvn pacakge
```

## Run

```shell
java -jar socket-log-receiver-1.0.0.jar \
  --output.path=/var/log/spring \
  --socket.port=4560
```

## Metrics

```shell
curl 'http://localhost:4550/actuator/metrics'
```


| Metics name | Type | Description |
| --- | --- | --- |
| socket.connection.active | Gauge | 连接数 |
| event.count | Counter | 收到的事件数 |
| event.unsupported.data.type.count | Counter | 无法识别的事件数 |
| store.queue.size | Gauge | 存储队列长度 |
| store.logger.count | Gauge | 存储器数量 |
| store.count | Counter | 存储的事件数 |
| store.discard.count | Counter | 抛弃的事件数 |
| store.error.count | Counter | 存储错误数 |
| store.response.time | Timer | 存储响应时间 |



# Difference between the splace binder and this fork

Additional:
- Support for non-persistent publish and subscribe
- Support for groups in direct subscription using #share subscription on topics.
- Reapply subscriptions on temporary queues after reconnect with more than 60 sec interruption
- Fixed startup error with anonymous queues when broker is under load
- Provide and cache JCSMPSessions as Bean to avoid multiple connections to the same broker
- Support large messages up to 1.2GB with chunking (need partitioned queues if using groups)

Not supported by fork on purpose:
- Batch processing - We judge this feature as unnecessary complex and an antipattern
- Selector - This is a JMS feature generating a lot of traffic and the same effect can be done easy by a simple filter
- TopicEndPoint - This is a JMS feature and an antipattern
- Multi maven project - a simple maven project does the job as well and is less expensive to maintain
- Polling endpoint - We want to enforce async message processing and don't support polling

#!/bin/sh
set -e
#预设java参数，自定义java参数用容器启动时配置环境变量JAVA_OPTS来实现，相同参数后者会覆盖前者
JAVA_DEFAULT_OPTS="
    -Dfile.encoding=UTF-8
    -Duser.timezone=Asia/Shanghai
    -XX:+UseContainerSupport
    -XX:+UnlockExperimentalVMOptions
    -XX:+UseCGroupMemoryLimitForHeap"
#启动服务
exec java $JAVA_DEFAULT_OPTS $JAVA_OPTS -jar app.jar $JAVA_ARGS
#!/bin/sh

#切换至脚本所在目录
shellDir=`dirname $0`
cd $shellDir

JAVA_DEFAULT_OPTS="
    -Dfile.encoding=UTF-8
    -Duser.timezone=Asia/Shanghai
"

JAVA_OPTS="
    -Xmx128m
"

nohup java -jar $JAVA_DEFAULT_OPTS $JAVA_OPTS proxy.jar >nohup.log 2>&1 &

echo '服务启动'

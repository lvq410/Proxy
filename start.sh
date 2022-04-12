JAVA_DEFAULT_OPTS="
    -Dfile.encoding=UTF-8
    -Duser.timezone=Asia/Shanghai
"

JAVA_OPTS="
"

nohup java -jar $JAVA_DEFAULT_OPTS $JAVA_OPTS proxy.jar >nohup.log 2>&1 &

echo '服务启动'

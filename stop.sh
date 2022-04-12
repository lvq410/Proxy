if [ ! -f "./pid" ]; then
  echo '服务未运行'
  exit 0
fi

pid=`cat ./pid`
echo "服务运行于进程$pid,关停中"

kill $pid

i=0
while [ $i -lt 10 ] && [ -f "./pid" ]
do

echo '等待服务关停中，剩余'$((10-i))'s'
sleep 1
let i++
done


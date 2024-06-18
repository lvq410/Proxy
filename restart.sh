#!/bin/sh

#切换至脚本所在目录
shellDir=`dirname $0`
cd $shellDir


set +e

./stop.sh

./start.sh

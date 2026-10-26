#!/bin/sh
# Publishes a test pattern with the camera name and wall-clock time burned in, to this
# MediaMTX. Args: label size rate codec kbps port path
label="$1"; size="$2"; rate="$3"; codec="$4"; kbps="$5"; port="$6"; path="$7"
fs=40; case "$size" in 640x*) fs=22;; esac
text="$label %{localtime\:%F %T}"
if [ "$codec" = h265 ]; then
  venc="libx265 -preset ultrafast -x265-params keyint=$rate:min-keyint=$rate:bframes=0:log-level=error"
else
  venc="libx264 -preset veryfast -tune zerolatency -g $rate -keyint_min $rate -bf 0"
fi
exec ffmpeg -hide_banner -loglevel error -re -f lavfi -i "testsrc2=size=$size:rate=$rate" \
  -vf "drawtext=fontfile=/fonts/arial.ttf:text='$text':fontsize=$fs:fontcolor=white:x=20:y=20:box=1:boxcolor=black@0.6" \
  -c:v $venc -b:v "${kbps}k" -pix_fmt yuv420p -f rtsp "rtsp://localhost:$port/$path"

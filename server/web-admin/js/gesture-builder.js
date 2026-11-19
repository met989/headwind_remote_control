function GestureBuilder(divGesture, remoteChat){
    this.divGesture = divGesture;
    this.remoteChat = remoteChat;

    this.swipeStartPosition = [0, 0];
    this.swipeStartMillis = 0;
    this.swipeInProcess = false;

    let obj = this;  // for event handlers

    this.mapCoordinates = function (e) {
        var videoEl = document.getElementById('streamingRemoteVideo');
        if (videoEl) {
            var rect = videoEl.getBoundingClientRect();
            var natW = (videoEl.videoWidth > 0) ? videoEl.videoWidth : (parseInt(videoEl.getAttribute('width'), 10) || rect.width);
            var natH = (videoEl.videoHeight > 0) ? videoEl.videoHeight : (parseInt(videoEl.getAttribute('height'), 10) || rect.height);
            if (rect.width > 0 && rect.height > 0 && natW > 0 && natH > 0) {
                var relX = Math.max(0, Math.min(rect.width, e.clientX - rect.left));
                var relY = Math.max(0, Math.min(rect.height, e.clientY - rect.top));
                return [
                    Math.round((relX / rect.width) * natW),
                    Math.round((relY / rect.height) * natH)
                ];
            }
        }
        return [Math.max(0, Math.round(e.offsetX)), Math.max(0, Math.round(e.offsetY))];
    }

    this.gestureStart = function (offsetX, offsetY){
        console.debug('gesture: starts on ', [offsetX, offsetY]);
        this.swipeInProcess = true;
        this.swipeStartMillis = Date.now();
        this.swipeStartPosition = [offsetX, offsetY];
    }

    this.gestureFinish = function (offsetX, offsetY){
        offsetX = offsetX > 0 ? offsetX : 0;
        offsetY = offsetY > 0 ? offsetY : 0;
        if(this.swipeInProcess){
            console.debug('gesture: ends on ', [offsetX, offsetY]);

            var swipeDuration = Math.max(1, Date.now() - this.swipeStartMillis);
            var swipeEndPosition = [offsetX, offsetY];
            var swipeType = '', swipeDataToSend = '';
            if (Math.abs(this.swipeStartPosition[0]-swipeEndPosition[0]) < 2 && Math.abs(this.swipeStartPosition[1]-swipeEndPosition[1]) < 2 ){
                swipeDataToSend = `tap,${this.swipeStartPosition[0]},${this.swipeStartPosition[1]},${swipeDuration}`;
            } else {
                swipeDataToSend = `swipe,${this.swipeStartPosition[0]},${this.swipeStartPosition[1]},${swipeEndPosition[0]},${swipeEndPosition[1]},${swipeDuration}`;
            }
            this.remoteChat.sendData(swipeDataToSend);
            this.swipeInProcess = false;
        }
    }

    this.divGesture.on('mousedown', function(e){
        $(e.target).css('cursor', 'pointer');
        var coords = obj.mapCoordinates(e);
        obj.gestureStart(coords[0], coords[1]);
        e.preventDefault();
    }).on('mouseup', function(e){
        $(e.target).css('cursor', 'auto');
        var coords = obj.mapCoordinates(e);
        obj.gestureFinish(coords[0], coords[1]);
        e.preventDefault();
    }).on('mouseleave', function(e){
        $(e.target).css('cursor', 'auto');
        var coords = obj.mapCoordinates(e);
        obj.gestureFinish(coords[0], coords[1]);
        e.preventDefault();
    });
}

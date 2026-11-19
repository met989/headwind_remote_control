function RemoteVideo(remoteVideoElem, videoLoader, videoStats) {
    this.streaming = null;
    this.remoteVideoElem = remoteVideoElem;
    this.videoLoader = videoLoader;
    this.videoStats = videoStats;
    this.stream = null;
    this.mountpointId = null;

    this.videoResolution = null;
    this.isVideoAlreadyPlayed = false;
    this.fitMode = false; // Default: 1:1 original size

    var obj = this;  // for event handlers

    this.getStreamVideotracks = function(){
        return this.stream ? this.stream.getVideoTracks() : [];
    }

    this.noRemoteVideo = function () {
        if (!this.isVideoAlreadyPlayed || window.debugUtils.isDebugEnabled()) {
            this.videoLoader.show();
        }
        console.debug('video: no remote');
    }

    this.hasRemoteVideo = function () {
        this.videoLoader.hide();
        console.debug('video: has remote');
    }

    this.setStreamingPluginHandle = function(streaming){
        this.streaming = streaming;
    }

    this.updateVideoSize = function() {
        var videoEl = this.remoteVideoElem.get(0);
        if (!videoEl) return;

        // Prefer live decoded stream dimensions, fallback to stored resolution, fallback to DOM attributes
        var natW = (videoEl.videoWidth > 0)
            ? videoEl.videoWidth
            : ((this.videoResolution && this.videoResolution[0] > 0)
                ? parseInt(this.videoResolution[0], 10)
                : (parseInt(this.remoteVideoElem.attr('width'), 10) || 540));
        var natH = (videoEl.videoHeight > 0)
            ? videoEl.videoHeight
            : ((this.videoResolution && this.videoResolution[1] > 0)
                ? parseInt(this.videoResolution[1], 10)
                : (parseInt(this.remoteVideoElem.attr('height'), 10) || 1064));

        if (!natW || !natH) return;

        var stage = document.getElementById('videoStage');

        // 1:1 original resolution mode (default)
        if (!this.fitMode) {
            if (stage) stage.classList.remove('fit-active');
            this.remoteVideoElem.css({
                width: natW + 'px',
                height: natH + 'px'
            });
            return;
        }

        // Fit-to-screen mode
        if (stage) stage.classList.add('fit-active');

        var stageW = (stage && stage.clientWidth > 100) ? stage.clientWidth : (window.innerWidth - 250 - 32);
        var stageH = (stage && stage.clientHeight > 100) ? stage.clientHeight : (window.innerHeight - 110);

        var padX = 20, padY = 20;
        if (stage && stage.clientWidth > 100) {
            var cs = window.getComputedStyle(stage);
            padX = (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0);
            padY = (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0);
        }

        var rawW = stageW - padX;
        var rawH = stageH - padY;
        var availW = Math.max(100, Math.floor(rawW - 6));
        var availH = Math.max(100, Math.floor(rawH - 6));

        var scale = Math.min(availW / natW, availH / natH);
        var dispW = Math.max(1, Math.floor(natW * scale));
        var dispH = Math.max(1, Math.floor(natH * scale));

        this.remoteVideoElem.css({
            width: dispW + 'px',
            height: dispH + 'px'
        });
    }

    this.toggleFitMode = function() {
        this.fitMode = !this.fitMode;
        this.updateVideoSize();
        return this.fitMode;
    }

    this.setResolution = function(w, h){
        var nw = parseInt(w, 10);
        var nh = parseInt(h, 10);
        if (nw > 0 && nh > 0) {
            this.videoResolution = [nw, nh];
            this.remoteVideoElem.attr('width', nw).attr('height', nh);
            this.updateVideoSize();
        }
    }

    this.setStream = function (stream) {
        let streamChanged = false;
        if (this.stream !== stream) {
            this.stream = stream;
            streamChanged = true;
        }

        if (this.getStreamVideotracks().length > 0) {
            if (streamChanged) {
                var videoEl = this.remoteVideoElem.get(0);
                videoEl.muted = true;
                Janus.attachMediaStream(videoEl, this.stream);
                var playPromise = videoEl.play();
                if (playPromise && typeof playPromise.catch === 'function') {
                    playPromise.catch(function(err) {
                        console.warn('video: autoplay catch', err);
                    });
                }
            }
            this.hasRemoteVideo();
            if (['chrome', 'firefox', 'safari'].indexOf(Janus.webRTCAdapter.browserDetails.browser) >= 0) {
                this.videoStats.start();
            }
        } else {
            this.noRemoteVideo();
            this.videoStats.stop();
        }
    }

    this.startStreamMountpoint = function (mountpointId, pin) {
        this.mountpointId = mountpointId;
        console.info("streaming: starting mountpoint id " + mountpointId + ' with pin ' + pin);

        var body = {"request": "watch", "id": mountpointId, "pin": pin};
        this.streaming.send({"message": body});
        this.noRemoteVideo();
    }

    // Native event listeners on video DOM element
    var videoEl = this.remoteVideoElem.get(0);
    if (videoEl) {
        videoEl.addEventListener("resize", function () {
            if (videoEl.videoWidth > 0 && videoEl.videoHeight > 0) {
                obj.setResolution(videoEl.videoWidth, videoEl.videoHeight);
            }
        });
        videoEl.addEventListener("loadedmetadata", function () {
            if (videoEl.videoWidth > 0 && videoEl.videoHeight > 0) {
                obj.setResolution(videoEl.videoWidth, videoEl.videoHeight);
            }
        });
    }

    this.remoteVideoElem.on("playing", function (e) {
        console.debug('video: playing event', e);

        if (obj.getStreamVideotracks().length > 0) {
            obj.videoStats.start();
            var videoDom = obj.remoteVideoElem.get(0);
            if (videoDom && videoDom.videoWidth > 0 && videoDom.videoHeight > 0) {
                obj.setResolution(videoDom.videoWidth, videoDom.videoHeight);
            } else {
                obj.updateVideoSize();
            }
            obj.isVideoAlreadyPlayed = true;
        } else {
            obj.videoStats.stop();
        }
    });

    this.remoteVideoElem.on("resize", function () {
        var videoDom = obj.remoteVideoElem.get(0);
        if (videoDom && videoDom.videoWidth > 0 && videoDom.videoHeight > 0) {
            obj.setResolution(videoDom.videoWidth, videoDom.videoHeight);
        }
    });

    $(window).on("resize", function () {
        obj.updateVideoSize();
    });

    // ResizeObserver on #videoStage for automatic adaptation to sidebar or window changes
    var stageEl = document.getElementById('videoStage');
    if (stageEl && window.ResizeObserver) {
        try {
            var ro = new ResizeObserver(function () {
                obj.updateVideoSize();
            });
            ro.observe(stageEl);
        } catch (e) {
            console.warn('ResizeObserver not supported:', e);
        }
    }

    $(document).on("fullscreenchange webkitfullscreenchange mozfullscreenchange MSFullscreenChange", function () {
        setTimeout(function () {
            obj.updateVideoSize();
        }, 100);
    });

    this.stopStreaming = function () {
        console.info('video: stopping streaming');
        this.streaming.send({"message": {"request": "stop"}});
        this.streaming.hangup();
        this.cleanup();
    }

    this.cleanup = function () {
        console.info('video: cleanup ..');
        this.videoStats.stop();
    }
}

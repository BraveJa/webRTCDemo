package com.onesun.mywebrtc;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.onesun.mywebrtc.signal.SignalClient;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class MyCallActivity extends AppCompatActivity implements SignalClient.OnSignalEventListener {
	//继承自 surface view
	private SurfaceViewRenderer mLocalSurfaceView;
	private SurfaceViewRenderer mRemoteSurfaceView;
	private TextView mLogcatView;
	//OpenGL ES
	private EglBase mRootEglBase;
	//纹理渲染帮助类
	private SurfaceTextureHelper mSurfaceTextureHelper;
	private PeerConnectionFactory mPeerConnectionFactory;
	private VideoCapturer mVideoCapturer;


	public static final String VIDEO_TRACK_ID = "1";//"ARDAMSv0";
	public static final String AUDIO_TRACK_ID = "2";//"ARDAMSa0";
	private VideoTrack mVideoTrack;
	private AudioTrack mAudioTrack;

	private static final int VIDEO_RESOLUTION_WIDTH = 1280;
	private static final int VIDEO_RESOLUTION_HEIGHT = 720;
	private static final int VIDEO_FPS = 30;
	private static final String TAG = "MyCallActivity";

	private String mState = "init";
	private PeerConnection mPeerConnection;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_my_call);
		initView();
		initPeerConnectionFactor();
		registerSocketLinsster();
		joinRoom();
	}

	@Override
	protected void onResume() {
		super.onResume();
		mVideoCapturer.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, VIDEO_FPS);
	}

	@Override
	protected void onPause() {
		super.onPause();
		try {
			mVideoCapturer.stopCapture();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		doLeave();
		mLocalSurfaceView.release();
		mRemoteSurfaceView.release();
		mVideoCapturer.dispose();
		mSurfaceTextureHelper.dispose();
		PeerConnectionFactory.stopInternalTracingCapture();
		PeerConnectionFactory.shutdownInternalTracer();
		mPeerConnectionFactory.dispose();
	}

	public void doLeave() {
		logcatOnUI("Leave room, Wait ...");
		hangup();

		SignalClient.getInstance().leaveRoom();

	}

	private void hangup() {
		logcatOnUI("Hangup Call, Wait ...");
		if (mPeerConnection == null) {
			return;
		}
		mPeerConnection.close();
		mPeerConnection = null;
		logcatOnUI("Hangup Done.");
		updateCallState(true);
	}

	private void logcatOnUI(String msg) {
		Log.i(TAG, msg);
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				String output = mLogcatView.getText() + "\n" + msg;
				mLogcatView.setText(output);
			}
		});
	}

	private void joinRoom() {
		String serverAddr = getIntent().getStringExtra("ServerAddr");
		String roomName = getIntent().getStringExtra("RoomName");
		//加入房间
		SignalClient.getInstance().joinRoom(serverAddr, roomName);
	}

	/**
	 * 注册socket事件的监听
	 */
	private void registerSocketLinsster() {
		SignalClient.getInstance().setSignalEventListener(this);
	}

	private void initView() {
		mLogcatView = findViewById(R.id.LogcatView);
		mLocalSurfaceView = findViewById(R.id.LocalSurfaceView);
		mRemoteSurfaceView = findViewById(R.id.RemoteSurfaceView);
		mRootEglBase = EglBase.create();

		//本地视频流渲染初始化
		mLocalSurfaceView.init(mRootEglBase.getEglBaseContext(), null);
		mLocalSurfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
		mLocalSurfaceView.setMirror(true);
		mLocalSurfaceView.setEnableHardwareScaler(false);

		//远程视频流渲染初始化
		mRemoteSurfaceView.init(mRootEglBase.getEglBaseContext(), null);
		mRemoteSurfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
		mRemoteSurfaceView.setMirror(true);
		mRemoteSurfaceView.setEnableHardwareScaler(true);
		mRemoteSurfaceView.setZOrderMediaOverlay(true);
	}

	private void initPeerConnectionFactor() {
		mPeerConnectionFactory = createPeerConnectionFactory();
		// NOTE: this _must_ happen while PeerConnectionFactory is alive!
		Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE);
		mSurfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", mRootEglBase.getEglBaseContext());

		//创建视频源 ,参数是否截屏
		VideoSource videoSource = mPeerConnectionFactory.createVideoSource(false);
		//创建捕获视频流
		mVideoCapturer = createVideoCapturer();
		//初始化视频铺货器
		mVideoCapturer.initialize(mSurfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());
		//视频源和视频轨绑定
		mVideoTrack = mPeerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
		mVideoTrack.setEnabled(true);
		//视频流用本地View控件显示
		mVideoTrack.addSink(mLocalSurfaceView);

		AudioSource audioSource = mPeerConnectionFactory.createAudioSource(new MediaConstraints());
		mAudioTrack = mPeerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
		mAudioTrack.setEnabled(true);


	}

	/**
	 * 判断使用camera1还是camera2
	 */
	private VideoCapturer createVideoCapturer() {
		if (Camera2Enumerator.isSupported(this)) {

			return createCameraCapturer(new Camera2Enumerator(this));
		} else {
			return createCameraCapturer(new Camera1Enumerator(true));
		}
	}

	private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
		String[] deviceNames = enumerator.getDeviceNames();
		for (String deviceName : deviceNames) {
			if (enumerator.isFrontFacing(deviceName)) {
				VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
				if (videoCapturer != null) return videoCapturer;
			}
		}

		for (String deviceName : deviceNames) {
			if (enumerator.isBackFacing(deviceName)) {
				VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
				if (videoCapturer != null) return videoCapturer;
			}
		}

		return null;
	}

	/**
	 * 创建PC工厂
	 *
	 * @return
	 */
	private PeerConnectionFactory createPeerConnectionFactory() {
		//创建视频编解码工厂

		VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(mRootEglBase.getEglBaseContext(),
				false,
				true);
		VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(mRootEglBase.getEglBaseContext());
		//初始化PC工厂参数
		PeerConnectionFactory.InitializationOptions initializationOptions = PeerConnectionFactory
				.InitializationOptions
				.builder(this)
				.setEnableInternalTracer(true)
				.createInitializationOptions();
		PeerConnectionFactory.initialize(initializationOptions);
		PeerConnectionFactory.Builder builder = PeerConnectionFactory.builder()
				.setVideoDecoderFactory(decoderFactory)
				.setVideoEncoderFactory(encoderFactory);
		builder.setOptions(null);

		return builder.createPeerConnectionFactory();
	}

	private void updateCallState(boolean idle) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if (idle) {
					mRemoteSurfaceView.setVisibility(View.GONE);
				} else {
					mRemoteSurfaceView.setVisibility(View.VISIBLE);
				}
			}
		});
	}

	/**
	 * setLocalDescription
	 * 发送answer
	 * 对端会收到answer
	 */
	public void doAnswerCall() {
		logcatOnUI("Answer Call, Wait ...");

		if (mPeerConnection == null) {
			mPeerConnection = createPeerConnection();
		}

		MediaConstraints sdpMediaConstraints = new MediaConstraints();
		Log.i(TAG, "Create answer ...");
		mPeerConnection.createAnswer(new SimpleSdpObserver() {
			@Override
			public void onCreateSuccess(SessionDescription sessionDescription) {
				Log.i(TAG, "Create answer success !");
				mPeerConnection.setLocalDescription(new SimpleSdpObserver(),
						sessionDescription);

				JSONObject message = new JSONObject();
				try {
					message.put("type", "answer");
					message.put("sdp", sessionDescription.description);
					SignalClient.getInstance().sendMessage(message);
				} catch (JSONException e) {
					e.printStackTrace();
				}
			}
		}, sdpMediaConstraints);
		updateCallState(false);
	}

	/**
	 * 都进入房间了,连接成功 ,sendMessage
	 * 创建offer
	 * setLocalDescription
	 * 发送sdp
	 * 这时对方会收到offer
	 * 对方会发送answer
	 * 然后会收到answer
	 */
	private void doStartCall() {
		logcatOnUI("Start Call, Wait ...");
		if (mPeerConnection == null) {
			mPeerConnection = createPeerConnection();
		}

		MediaConstraints mediaConstraints = new MediaConstraints();
		mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
		mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
		//不打开dtls无法和web端通信
		mediaConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
		mPeerConnection.createOffer(new SimpleSdpObserver() {
			@Override
			public void onCreateSuccess(SessionDescription sessionDescription) {
				Log.i(TAG, "Create local offer success: \n" + sessionDescription.description);
				mPeerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
				JSONObject message = new JSONObject();
				try {
					message.put("type", "offer");
					message.put("sdp", sessionDescription.description);
					SignalClient.getInstance().sendMessage(message);
				} catch (JSONException e) {
					e.printStackTrace();
				}
			}
		}, mediaConstraints);
	}

	public PeerConnection createPeerConnection() {
		Log.i(TAG, "Create PeerConnection ...");
		LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<>();
		//搭建好stun服务器可以加上配置
		/*PeerConnection.IceServer iceServer = PeerConnection.IceServer.builder("")
				.setPassword("")
				.setUsername("")
				.createIceServer();
		iceServers.add(iceServer);*/

		PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
		// TCP candidates are only useful when connecting to a server that supports
		// ICE-TCP.
		rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
		//rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
		//rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
		rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
		// Use ECDSA encryption.
		//rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
		// Enable DTLS for normal calls and disable for loopback calls.
		rtcConfig.enableDtlsSrtp = true;
		//rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
		PeerConnection connection = mPeerConnectionFactory.createPeerConnection(rtcConfig, mPeerConnectionObserver);
		if (connection == null) {
			return null;
		}
		//pc音视频轨
		List<String> mediaStreamLabels = Collections.singletonList("ARDAMS");
		connection.addTrack(mVideoTrack, mediaStreamLabels);
		connection.addTrack(mAudioTrack, mediaStreamLabels);

		return connection;
	}

	private PeerConnection.Observer mPeerConnectionObserver = new PeerConnection.Observer() {
		@Override
		public void onSignalingChange(PeerConnection.SignalingState signalingState) {
			Log.i(TAG, "onSignalingChange: " + signalingState);
		}

		@Override
		public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
			Log.i(TAG, "onIceConnectionChange: " + iceConnectionState);
		}

		@Override
		public void onIceConnectionReceivingChange(boolean b) {
			Log.i(TAG, "onIceConnectionChange: " + b);
		}

		@Override
		public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
			Log.i(TAG, "onIceGatheringChange: " + iceGatheringState);
		}

		@Override
		public void onIceCandidate(IceCandidate iceCandidate) {
			Log.i(TAG, "onIceCandidate: " + iceCandidate);

			try {
				JSONObject message = new JSONObject();
				//message.put("userId", RTCSignalClient.getInstance().getUserId());
				message.put("type", "candidate");
				message.put("label", iceCandidate.sdpMLineIndex);
				message.put("id", iceCandidate.sdpMid);
				message.put("candidate", iceCandidate.sdp);
				SignalClient.getInstance().sendMessage(message);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}

		@Override
		public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
			for (int i = 0; i < iceCandidates.length; i++) {
				Log.i(TAG, "onIceCandidatesRemoved: " + iceCandidates[i]);
			}
			mPeerConnection.removeIceCandidates(iceCandidates);
		}

		@Override
		public void onAddStream(MediaStream mediaStream) {
			Log.i(TAG, "onAddStream: " + mediaStream.videoTracks.size());
		}

		@Override
		public void onRemoveStream(MediaStream mediaStream) {
			Log.i(TAG, "onRemoveStream");
		}

		@Override
		public void onDataChannel(DataChannel dataChannel) {
			Log.i(TAG, "onDataChannel");
		}

		@Override
		public void onRenegotiationNeeded() {
			Log.i(TAG, "onRenegotiationNeeded");
		}

		@Override
		public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
			MediaStreamTrack track = rtpReceiver.track();
			if (track instanceof VideoTrack) {
				Log.i(TAG, "onAddVideoTrack");
				VideoTrack remoteVideoTrack = (VideoTrack) track;
				remoteVideoTrack.setEnabled(true);
				remoteVideoTrack.addSink(mRemoteSurfaceView);
			}
		}
	};

	@Override
	public void onConnected() {

	}

	@Override
	public void onConnecting() {

	}

	@Override
	public void onDisconnected() {

	}

	@Override
	public void onUserJoined(String roomName, String userID) {

	}

	@Override
	public void onUserLeaved(String roomName, String userID) {

	}

	//otherJoined 当其他用户加入进来了
	@Override
	public void onRemoteUserJoined(String roomName) {
		logcatOnUI("Remote User Joined, room: " + roomName);

		if (mState.equals("joined_unbind")) {
			if (mPeerConnection == null) {
				mPeerConnection = createPeerConnection();
			}
		}

		mState = "joined_conn";
		//调用call， 进行媒体协商
		doStartCall();
	}

	@Override
	public void onRemoteUserLeaved(String roomName, String userID) {
		logcatOnUI("Remote User Leaved, room: " + roomName + "uid:"  + userID);
		mState = "joined_unbind";

		if(mPeerConnection !=null ){
			mPeerConnection.close();
			mPeerConnection = null;
		}
	}

	@Override
	public void onRoomFull(String roomName, String userID) {
		logcatOnUI("The Room is Full, room: " + roomName + "uid:"  + userID);
		mState = "leaved";

		if(mLocalSurfaceView != null) {
			mLocalSurfaceView.release();
			mLocalSurfaceView = null;
		}

		if(mRemoteSurfaceView != null) {
			mRemoteSurfaceView.release();
			mRemoteSurfaceView = null;
		}

		if(mVideoCapturer != null) {
			mVideoCapturer.dispose();
			mVideoCapturer = null;
		}

		if(mSurfaceTextureHelper != null) {
			mSurfaceTextureHelper.dispose();
			mSurfaceTextureHelper = null;

		}

		PeerConnectionFactory.stopInternalTracingCapture();
		PeerConnectionFactory.shutdownInternalTracer();

		if(mPeerConnectionFactory !=null) {
			mPeerConnectionFactory.dispose();
			mPeerConnectionFactory = null;
		}

		finish();
	}

	@Override
	public void onMessage(JSONObject message) {
		Log.i(TAG, "onMessage: " + message);

		try {
			String type = message.getString("type");
			if (type.equals("offer")) {
				onRemoteOfferReceived(message);
			} else if (type.equals("answer")) {
				onRemoteAnswerReceived(message);
			} else if (type.equals("candidate")) {
				onRemoteCandidateReceived(message);
			} else {
				Log.w(TAG, "the type is invalid: " + type);
			}
		} catch (JSONException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 收到对端offer的sdp描述
	 * setRemoteDescription
	 * 发送answer
	 *
	 * @param message
	 */
	private void onRemoteOfferReceived(JSONObject message) {
		logcatOnUI("Receive Remote Call ...");

		if (mPeerConnection == null) {
			mPeerConnection = createPeerConnection();
		}

		try {
			String description = message.getString("sdp");
			mPeerConnection.setRemoteDescription(
					new SimpleSdpObserver(),
					new SessionDescription(
							SessionDescription.Type.OFFER,
							description));
			doAnswerCall();
		} catch (JSONException e) {
			e.printStackTrace();
		}

	}
	public static class SimpleSdpObserver implements SdpObserver {
		@Override
		public void onCreateSuccess(SessionDescription sessionDescription) {
			Log.i(TAG, "SdpObserver: onCreateSuccess !");
		}

		@Override
		public void onSetSuccess() {
			Log.i(TAG, "SdpObserver: onSetSuccess");
		}

		@Override
		public void onCreateFailure(String msg) {
			Log.e(TAG, "SdpObserver onCreateFailure: " + msg);
		}

		@Override
		public void onSetFailure(String msg) {

			Log.e(TAG, "SdpObserver onSetFailure: " + msg);
		}
	}
	/**
	 * 收到对端的answer
	 * setRemoteDescription
	 *
	 * @param message
	 */
	private void onRemoteAnswerReceived(JSONObject message) {
		logcatOnUI("Receive Remote Answer ...");
		try {
			String description = message.getString("sdp");
			mPeerConnection.setRemoteDescription(
					new SimpleSdpObserver(),
					new SessionDescription(
							SessionDescription.Type.ANSWER,
							description));
		} catch (JSONException e) {
			e.printStackTrace();
		}
		updateCallState(false);
	}

	/**
	 * 更新IceCandidate
	 *
	 * @param message
	 */
	private void onRemoteCandidateReceived(JSONObject message) {
		logcatOnUI("Receive Remote Candidate ...");
		try {
			IceCandidate remoteIceCandidate =
					new IceCandidate(message.getString("id"),
							message.getInt("label"),
							message.getString("candidate"));

			mPeerConnection.addIceCandidate(remoteIceCandidate);
		} catch (JSONException e) {
			e.printStackTrace();
		}
	}
}

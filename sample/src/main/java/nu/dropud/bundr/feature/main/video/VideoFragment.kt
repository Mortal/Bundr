package nu.dropud.bundr.feature.main.video

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.AsyncTask
import android.os.Bundle
import android.os.IBinder
import android.support.design.widget.CoordinatorLayout
import android.support.design.widget.Snackbar
import android.view.View
import nu.dropud.bundr.R
import nu.dropud.bundr.app.App
import nu.dropud.bundr.common.extension.areAllPermissionsGranted
import nu.dropud.bundr.common.extension.startAppSettings
import nu.dropud.bundr.feature.base.BaseMvpFragment
import nu.dropud.bundr.webrtc.service.WebRtcService
import nu.dropud.bundr.webrtc.service.WebRtcServiceListener
import kotlinx.android.synthetic.main.fragment_video.*
import org.webrtc.PeerConnection
import timber.log.Timber
import android.os.CountDownTimer
import android.provider.MediaStore
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import nu.dropud.bundr.app.InitActivity


class VideoFragment constructor() : BaseMvpFragment<VideoFragmentView, VideoFragmentPresenter>(), VideoFragmentView, WebRtcServiceListener {

    companion object {
        val TAG: String = VideoFragment::class.java.name
        val instance = VideoFragment()

        private const val KEY_IN_CHAT = "key:in_chat"
        private const val CONNECT_BUTTON_ANIMATION_DURATION_MS = 500L
    }

    private lateinit var serviceConnection: ServiceConnection

    override fun getLayoutId() = R.layout.fragment_video

    override fun retrievePresenter() = App.getApplicationComponent(context).videoFragmentComponent().videoFragmentPresenter()

    var service: WebRtcService? = null
    var localReady : Boolean = false
    var remoteReady : Boolean = false
    var counterStarted: Boolean = false
    var wasDisconnectedByOtherVar : Boolean = false

    var oopsiePlayer: MediaPlayer? = null
    var countdownPlayer: MediaPlayer? = null

    var readySnackbar: Snackbar? = null

    override fun wasDisconnectedByOther() {
        wasDisconnectedByOtherVar = true
    }

    override val remoteUuid get() = service?.getRemoteUuid()

    override fun onAttach(context: Context?) {
        super.onAttach(context)
    }

    override fun onDetach() {
        super.onDetach()
    }

    fun dismissReadySnackbar() {
        readySnackbar?.let {
            it.dismiss()
            readySnackbar = null
        }
    }

    override fun addBindings() {
        service!!.listenForReadyState({ isReady ->
            remoteReady = isReady

            if (remoteReady) {
                if (readySnackbar == null) {
                    readySnackbar = showSnackbarMessage(R.string.your_partner_is_ready, Snackbar.LENGTH_INDEFINITE)
                }
            } else {
                dismissReadySnackbar()
            }

            checkBothReady()
        })
    }

    override fun onResume() {
        super.onResume()
        wasDisconnectedByOtherVar = false
        //connect()
    }

    override fun onPause() {
        super.onPause()
        if(!wasDisconnectedByOtherVar) {
            getPresenter().disconnectByUser()
        }
        service?.let {
            it.detachViews()
            unbindService()
        }
        if (!activity.isChangingConfigurations) {
            service?.showBackgroundWorkWarning()
        }
    }

    fun pushNotification() {
        object: AsyncTask<Unit, Unit, Unit>() {
            val SERVER_KEY = "AAAAzPMglzY:APA91bEJDqMpPhVGNDXns32f0_2ovemzaVPl58estrPD_ohk4rAASGm3QYeszRQOKHssRIxxu3Rgg-Vverf6P6Rmu44tIHnG0zWWWVFaGbCZOxfPl2fL4mmu-JhGNn1ldE2pRhtSMVVe"
            val URL = "https://fcm.googleapis.com/fcm/send"

            override fun doInBackground(vararg params: Unit?): Unit {
                khttp.post(URL,
                        headers = mapOf("Authorization" to "key=${SERVER_KEY}"),
                        json = mapOf(
                                "to" to "/topics/hot_singles_in_your_area",
                                "data" to mapOf(
                                        "message" to "Memes"
                                ),
                                "notification" to mapOf(
                                        "title" to "Chug partner needed",
                                        "body" to "Someone is waiting for you ;^)",
                                        "sound" to "default",
                                        "click_action" to "NOTIFICATION_ACTIVITY",
                                        "icon" to "fcm_push_icon"
                                )
                        ))
            }
        }.execute()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (buttonPanel.layoutParams as CoordinatorLayout.LayoutParams).behavior = MoveUpBehavior()
        (localVideoView.layoutParams as CoordinatorLayout.LayoutParams).behavior = MoveUpBehavior()
        activity.volumeControlStream = AudioManager.STREAM_VOICE_CALL

        oopsiePlayer = MediaPlayer.create(context, R.raw.oopsiewoopsie)
        countdownPlayer = MediaPlayer.create(context, R.raw.countdown)

        FirebaseDatabase.getInstance().getReference("online_devices/").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot?) {
                if (dataSnapshot?.childrenCount == 0L) {
                    pushNotification()
                }
            }

            override fun onCancelled(databaseError: DatabaseError?) {
            }
        })

        connect()

        disconnectButton.setOnClickListener {
            if (service?.sendReadyState(!localReady)!!) {
                localReady = !localReady
                checkBothReady()
                if (localReady) {
                    disconnectButton.setImageResource(R.drawable.beerready)
                } else {
                    disconnectButton.setImageResource(R.drawable.beernotready)
                }
            } else {
                showSnackbarMessage(R.string.too_fast_m8, Snackbar.LENGTH_LONG)
                oopsiePlayer?.start()
            }

            //val rem = remoteUuid
            //getPresenter().disconnectByUser()
        }

        returnCross.setOnClickListener {
            getPresenter().disconnectByUser()
        }

        switchCameraButton.setOnClickListener {
            service?.switchCamera()
        }

        cameraEnabledToggle.setOnCheckedChangeListener { _, enabled ->
            service?.enableCamera(enabled)
        }

        microphoneEnabledToggle.setOnCheckedChangeListener { _, enabled ->
            service?.enableMicrophone(enabled)
        }
    }


    private fun checkBothReady() {
        if(localReady && remoteReady) {
            if(counterStarted) return

            dismissReadySnackbar()

            val countdownInterval : Long = 1000
            val totalTime = 5 * countdownInterval

            countdownPlayer?.start()

            counterStarted = true
            disconnectButton.visibility = View.GONE
            counterText.bringToFront()
            counterText.visibility = View.VISIBLE

            object : CountDownTimer(totalTime, countdownInterval) {
                override fun onTick(millisUntilFinished: Long) {
                    if(millisUntilFinished > 2 * countdownInterval) {
                        counterText.text = ""+((millisUntilFinished / countdownInterval)-1)
                    }
                    else {
                        counterText.text = "GO!"
                    }
                }

                override fun onFinish() {
                    counterText.visibility = View.GONE
                }
            }.start()
        }
    }

    override fun onStart() {
        super.onStart()
        service?.hideBackgroundWorkWarning()
    }

    /** override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (remoteVideoView.visibility == View.VISIBLE) {
            outState.putBoolean(KEY_IN_CHAT, true)
        }
    } **/

    override fun onDestroy() {
        super.onDestroy()
        if (!activity.isChangingConfigurations) disconnect()
    }

    override fun attachService() {
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
                onWebRtcServiceConnected((iBinder as (WebRtcService.LocalBinder)).service)
                getPresenter().startRoulette()
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                onWebRtcServiceDisconnected()
            }
        }
        startAndBindWebRTCService(serviceConnection)
    }

    override fun criticalWebRTCServiceException(throwable: Throwable) {
        unbindService()
        showSnackbarMessage(R.string.error_web_rtc_error, Snackbar.LENGTH_LONG)
        Timber.e(throwable, "Critical WebRTC service error")
    }

    override fun connectionStateChange(iceConnectionState: PeerConnection.IceConnectionState) {
        getPresenter().connectionStateChange(iceConnectionState)
    }

    override fun connectTo(uuid: String) {
        service?.offerDevice(uuid)
    }

    override fun disconnect() {
        service?.let {
            it.stopSelf()
            unbindService()
        }
    }

    private fun unbindService() {
        service?.let {
            it.detachServiceActionsListener()
            context.unbindService(serviceConnection)
            service = null
        }
    }

    override fun showCamViews() {
         buttonPanel.visibility = View.VISIBLE
        remoteVideoView.visibility = View.VISIBLE
        localVideoView.visibility = View.VISIBLE
    }

    override fun showStartRouletteView() {
        /**buttonPanel.visibility = View.GONE
        remoteVideoView.visibility = View.GONE
        localVideoView.visibility = View.GONE **/
        startActivity(Intent(activity, InitActivity::class.java))

    }

    override fun showErrorWhileChoosingRandom() {
        showSnackbarMessage(R.string.error_choosing_random_partner, Snackbar.LENGTH_LONG)
    }

    override fun showNoOneAvailable() {
        showSnackbarMessage(R.string.msg_no_one_available, Snackbar.LENGTH_LONG)
    }

    override fun showLookingForPartnerMessage() {
        showSnackbarMessage(R.string.msg_looking_for_partner, Snackbar.LENGTH_SHORT)
    }

    override fun showOtherPartyFinished() {
        showSnackbarMessage(R.string.msg_other_party_finished, Snackbar.LENGTH_SHORT)
    }

    override fun showConnectedMsg() {
        showSnackbarMessage(R.string.msg_connected_to_other_party, Snackbar.LENGTH_LONG)
    }

    override fun showWillTryToRestartMsg() {
        showSnackbarMessage(R.string.msg_will_try_to_restart_msg, Snackbar.LENGTH_LONG)
    }

    private fun initAlreadyRunningConnection() {
        showCamViews()
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
                onWebRtcServiceConnected((iBinder as (WebRtcService.LocalBinder)).service)
                getPresenter().listenForDisconnectOrders()
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                onWebRtcServiceDisconnected()
            }
        }
        startAndBindWebRTCService(serviceConnection)
    }

    private fun startAndBindWebRTCService(serviceConnection: ServiceConnection) {
        WebRtcService.startService(context)
        WebRtcService.bindService(context, serviceConnection)
    }

    fun connect() {
        getPresenter().connect()
    }

    private fun showNoPermissionsSnackbar() {
        view?.let {
            Snackbar.make(it, R.string.msg_permissions, Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_settings) {
                        try {
                            context.startAppSettings()
                        } catch (e: ActivityNotFoundException) {
                            showSnackbarMessage(R.string.error_permissions_couldnt_start_settings, Snackbar.LENGTH_LONG)
                        }
                    }
                    .show()
        }
    }

    private fun onWebRtcServiceConnected(service: WebRtcService) {
        Timber.d("Service connected")
        this.service = service
        service.attachLocalView(localVideoView)
        service.attachRemoteView(remoteVideoView)
        syncButtonsState(service)
        service.attachServiceActionsListener(webRtcServiceListener = this)
    }

    private fun syncButtonsState(service: WebRtcService) {
        cameraEnabledToggle.isChecked = service.isCameraEnabled()
        microphoneEnabledToggle.isChecked = service.isMicrophoneEnabled()
    }

    private fun onWebRtcServiceDisconnected() {
        Timber.d("Service disconnected")
    }
}
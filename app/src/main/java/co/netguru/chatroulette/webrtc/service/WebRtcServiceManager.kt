package co.netguru.chatroulette.webrtc.service

import co.netguru.chatroulette.common.extension.ChildEventAdded
import co.netguru.chatroulette.common.util.RxUtils
import co.netguru.chatroulette.data.firebase.FirebaseIceCandidates
import co.netguru.chatroulette.data.firebase.FirebaseIceServers
import co.netguru.chatroulette.data.firebase.FirebaseSignalingAnswers
import co.netguru.chatroulette.data.firebase.FirebaseSignalingOffers
import co.netguru.chatroulette.data.model.IceCandidateFirebase
import co.netguru.chatroulette.webrtc.PeerConnectionListener
import co.netguru.chatroulette.webrtc.WebRtcAnsweringPartyListener
import co.netguru.chatroulette.webrtc.WebRtcClient
import co.netguru.chatroulette.webrtc.WebRtcOfferingActionListener
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import timber.log.Timber
import javax.inject.Inject


class WebRtcServiceManager @Inject constructor(
        private val webRtcClient: WebRtcClient,
        private val firebaseSignalingAnswers: FirebaseSignalingAnswers,
        private val firebaseSignalingOffers: FirebaseSignalingOffers,
        private val firebaseIceCandidates: FirebaseIceCandidates,
        private val firebaseIceServers: FirebaseIceServers) {

    private val disposables = CompositeDisposable()

    private lateinit var remoteUuid: String
    private var finishedInitializing = false
    private var shouldCreateOffer = false
    private var isOfferingParty = false

    init {
        loadIceServers()
    }

    fun offerDevice(deviceUuid: String) {
        isOfferingParty = true
        this.remoteUuid = deviceUuid
        listenForIceCandidates()
        if (finishedInitializing) webRtcClient.createOffer() else shouldCreateOffer = true
    }

    fun attachRemoteView(remoteView: SurfaceViewRenderer) {
        webRtcClient.attachRemoteView(remoteView, null)
    }

    fun attachLocalView(localView: SurfaceViewRenderer) {
        webRtcClient.attachLocalView(localView, null)
    }

    fun detachViews() {
        webRtcClient.detachViews()
    }

    fun destroy() {
        disposables.dispose()
        webRtcClient.detachViews()
        if (finishedInitializing) webRtcClient.releasePeerConnection()
        webRtcClient.dispose()
    }

    private fun loadIceServers() {
        disposables += firebaseIceServers.getIceServers()
                .subscribeBy(
                        onSuccess = {
                            listenForOffers()
                            initializeWebRtc(it)
                        },
                        onError = {
                            //todo mvpView?.showServersRetrievingError()
                        }
                )
    }

    private fun initializeWebRtc(it: List<PeerConnection.IceServer>) {
        webRtcClient.initializePeerConnection(it, object : PeerConnectionListener {
            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED && isOfferingParty) {
                    webRtcClient.restart()
                }
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                sendIceCandidates(iceCandidate)
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                removeIceCandidates(iceCandidates)
            }

        }, object : WebRtcOfferingActionListener {
            override fun onError(error: String) {
                Timber.d("Error in offering party: $error")
            }

            override fun onOfferRemoteDescription(localSessionDescription: SessionDescription) {
                listenForAnswers()
                sendOffer(localSessionDescription)
            }

        }, object : WebRtcAnsweringPartyListener {
            override fun onError(error: String) {
                Timber.d("Error in answering party: $error")
            }

            override fun onSuccess(localSessionDescription: SessionDescription) {
                sendAnswer(localSessionDescription)
            }
        })
        if (shouldCreateOffer) webRtcClient.createOffer()
        finishedInitializing = true
    }


    private fun listenForIceCandidates() {
        disposables += firebaseIceCandidates.get(remoteUuid)
                .compose(RxUtils.applyFlowableIoSchedulers())
                .subscribeBy(
                        onNext = {
                            Timber.d("Next ice: $it")
                            if (it is ChildEventAdded) {
                                webRtcClient.addIceCandidate(it.data)
                            } else {
                                webRtcClient.removeIceCandidate(it.data)
                            }
                        },
                        onError = {
                            Timber.e(it, "Error while listening for signals")
                        }
                )
    }

    private fun sendIceCandidates(iceCandidate: IceCandidate) {
        disposables += firebaseIceCandidates.send(IceCandidateFirebase.createFromIceCandidate(iceCandidate))
                .compose(RxUtils.applyCompletableIoSchedulers())
                .subscribeBy(
                        onError = {
                            Timber.e(it, "Error while sending message")
                        },
                        onComplete = {
                            Timber.d("Ice message sent")
                        }
                )
    }

    private fun removeIceCandidates(iceCandidates: Array<IceCandidate>) {
        disposables += firebaseIceCandidates.remove(IceCandidateFirebase.createFromIceCandidates(iceCandidates))
                .compose(RxUtils.applyCompletableIoSchedulers())
                .subscribeBy(
                        onComplete = {
                            Timber.d("Ice candidates successfully removed")
                        },
                        onError = {
                            Timber.d("Error while removing ice candidates $it")
                        }
                )
    }

    private fun sendOffer(localDescription: SessionDescription) {
        disposables += firebaseSignalingOffers.create(remoteUuid, localDescription)
                .compose(RxUtils.applyCompletableIoSchedulers())
                .subscribeBy(
                        onComplete = {
                            Timber.d("description set")
                        },
                        onError = {
                            Timber.e(it, "Error occurred while setting description")
                        }
                )
    }

    private fun listenForOffers() {
        disposables += firebaseSignalingOffers.listen()
                .compose(RxUtils.applyFlowableIoSchedulers())
                .subscribeBy(
                        onNext = {
                            val data = it.data
                            if (data != null) {
                                remoteUuid = it.data.senderUuid
                                listenForIceCandidates()
                                webRtcClient.handleRemoteOffer(it.data.toSessionDescription())
                            } else {
                                //todo
                            }
                        },
                        onError = {
                            Timber.e(it, "Error while listening for offers")
                        }
                )
    }

    private fun sendAnswer(localDescription: SessionDescription) {
        disposables += firebaseSignalingAnswers.create(remoteUuid, localDescription)
                .compose(RxUtils.applyCompletableIoSchedulers())
                .subscribeBy(
                        onError = {
                            Timber.e(it, "Error occurred while sending answer")
                        }
                )
    }

    private fun listenForAnswers() {
        disposables += firebaseSignalingAnswers.listen()
                .compose(RxUtils.applyFlowableIoSchedulers())
                .subscribeBy(
                        onError = {
                            Timber.e(it, "Error while listening for answers")
                        },
                        onNext = {
                            Timber.d("Next answer $it")
                            val data = it.data
                            if (data != null) {
                                webRtcClient.handleRemoteAnswer(it.data.toSessionDescription())
                            } else {
                                //todo
                            }
                        }
                )
    }

}
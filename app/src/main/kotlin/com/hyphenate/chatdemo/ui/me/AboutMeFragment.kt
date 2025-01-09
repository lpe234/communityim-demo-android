package com.hyphenate.chatdemo.ui.me

import android.Manifest
import android.app.Activity
import android.app.Activity.RESULT_OK
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.hyphenate.chatdemo.DemoApplication
import com.hyphenate.chatdemo.DemoHelper
import com.hyphenate.chatdemo.R
import com.hyphenate.chatdemo.callkit.CallKitManager.showSelectDialog
import com.hyphenate.chatdemo.common.DemoConstant
import com.hyphenate.chatdemo.common.DeveloperModeHelper
import com.hyphenate.chatdemo.common.PresenceCache
import com.hyphenate.chatdemo.controller.PresenceController
import com.hyphenate.chatdemo.databinding.DemoFragmentAboutMeBinding
import com.hyphenate.chatdemo.interfaces.IPresenceResultView
import com.hyphenate.chatdemo.ui.login.LoginActivity
import com.hyphenate.chatdemo.ui.me.controller.CameraAndCroppingController
import com.hyphenate.chatdemo.utils.CameraAndCropFileUtils
import com.hyphenate.chatdemo.utils.EasePresenceUtil
import com.hyphenate.chatdemo.viewmodel.LoginViewModel
import com.hyphenate.chatdemo.viewmodel.PresenceViewModel
import com.hyphenate.chatdemo.viewmodel.ProfileInfoViewModel
import com.hyphenate.easeui.ChatUIKitClient
import com.hyphenate.easeui.base.ChatUIKitBaseFragment
import com.hyphenate.easeui.common.ChatClient
import com.hyphenate.easeui.common.ChatImageUtils
import com.hyphenate.easeui.common.ChatLog
import com.hyphenate.easeui.common.ChatPresence
import com.hyphenate.easeui.common.bus.ChatUIKitFlowBus
import com.hyphenate.easeui.common.dialog.CustomDialog
import com.hyphenate.easeui.common.dialog.SimpleListSheetDialog
import com.hyphenate.easeui.common.extensions.catchChatException
import com.hyphenate.easeui.common.extensions.dpToPx
import com.hyphenate.easeui.common.extensions.mainScope
import com.hyphenate.easeui.common.extensions.showToast
import com.hyphenate.easeui.common.permission.PermissionCompat
import com.hyphenate.easeui.common.utils.ChatUIKitCompat
import com.hyphenate.easeui.common.utils.ChatUIKitFileUtils
import com.hyphenate.easeui.configs.setStatusStyle
import com.hyphenate.easeui.feature.contact.ChatUIKitBlockListActivity
import com.hyphenate.easeui.interfaces.SimpleListSheetItemClickListener
import com.hyphenate.easeui.model.ChatUIKitEvent
import com.hyphenate.easeui.model.ChatUIKitMenuItem
import com.hyphenate.easeui.widget.ChatUIKitCustomAvatarView
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class AboutMeFragment: ChatUIKitBaseFragment<DemoFragmentAboutMeBinding>(), View.OnClickListener,
    ChatUIKitCustomAvatarView.OnPresenceClickListener, IPresenceResultView {

    /**
     * The clipboard manager.
     */
    private val clipboard by lazy { mContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    private val cameraAndCroppingController: CameraAndCroppingController by lazy {
        CameraAndCroppingController(mContext)
    }

    private lateinit var loginViewModel: LoginViewModel

    private val presenceViewModel by lazy { ViewModelProvider(this)[PresenceViewModel::class.java] }
    private val presenceController by lazy { PresenceController(mContext,presenceViewModel) }

    //
    private var showSelectDialog:SimpleListSheetDialog? = null
    private lateinit var model: ProfileInfoViewModel
    private var imageUri: Uri?= null

    companion object{
        private val TAG = AboutMeFragment::class.java.simpleName

        //
        private val REQUEST_TAKE_PHOTO = 0
        private val REQUEST_SELECT_IMAGE_IN_ALBUM = 1

        val IMAGE_REQUEST_CODE = 100
        private const val REQUEST_CODE_STORAGE_PICTURE = 111
        private const val REQUEST_CODE_CAMERA = 112
        private const val REQUEST_CODE_LOCAL_EDIT = 113
        private const val RESULT_CODE_CAMERA = 114
        private const val RESULT_CODE_LOCAL = 115
        private const val RESULT_CODE_UPDATE_NAME = 116
        private const val RESULT_REFRESH = "isRefresh"
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): DemoFragmentAboutMeBinding {
        return DemoFragmentAboutMeBinding.inflate(inflater)
    }

    override fun initView(savedInstanceState: Bundle?) {
        super.initView(savedInstanceState)
        initPresence()
        initStatus()
    }
    override fun initViewModel() {
        super.initViewModel()
        loginViewModel = ViewModelProvider(this)[LoginViewModel::class.java]
        presenceViewModel.attachView(this)
    }

    override fun initListener() {
        super.initListener()
        binding?.run {
            epPresence.setOnPresenceClickListener(this@AboutMeFragment)
            tvNumber.setOnClickListener(this@AboutMeFragment)
            itemPresence.setOnClickListener(this@AboutMeFragment)
            itemInformation.setOnClickListener(this@AboutMeFragment)
            itemCurrency.setOnClickListener(this@AboutMeFragment)
            itemNotify.setOnClickListener(this@AboutMeFragment)
            itemPrivacy.setOnClickListener(this@AboutMeFragment)
            itemAbout.setOnClickListener(this@AboutMeFragment)
            aboutMeLogout.setOnClickListener(this@AboutMeFragment)
            aboutMeAccountCancellation.setOnClickListener(this@AboutMeFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    override fun initData() {
        super.initData()
        fetchCurrentPresence()
        initEvent()
        //
        model = ViewModelProvider(this)[ProfileInfoViewModel::class.java]
    }

    private fun initEvent() {
        ChatUIKitFlowBus.with<ChatUIKitEvent>(ChatUIKitEvent.EVENT.UPDATE.name).register(this) {
            if (it.isPresenceChange && it.message.equals(ChatUIKitClient.getCurrentUser()?.id) ) {
                updatePresence()
            }
        }

        ChatUIKitFlowBus.with<ChatUIKitEvent>(ChatUIKitEvent.EVENT.UPDATE + ChatUIKitEvent.TYPE.CONTACT).register(this) {
            if (it.isContactChange && it.event == DemoConstant.EVENT_UPDATE_SELF) {
                updatePresence(true)
            }
        }
    }

    private fun initPresence(){
        binding?.run {
            var name:String? = ChatClient.getInstance().currentUser
            val id = getString(R.string.main_about_me_id,ChatClient.getInstance().currentUser)
            ChatUIKitClient.getConfig()?.avatarConfig?.setStatusStyle(epPresence.getStatusView(),4.dpToPx(mContext),
                ContextCompat.getColor(mContext, com.hyphenate.easeui.R.color.ease_color_background))
            epPresence.setPresenceStatusMargin(end = -4, bottom = -4)
            epPresence.setPresenceStatusSize(resources.getDimensionPixelSize(com.hyphenate.easeui.R.dimen.ease_contact_status_icon_size))

            val layoutParams = epPresence.getUserAvatar().layoutParams
            layoutParams.width = 100.dpToPx(mContext)
            layoutParams.height = 100.dpToPx(mContext)
            epPresence.getUserAvatar().layoutParams = layoutParams

            ChatUIKitClient.getCurrentUser()?.let {
                epPresence.setUserAvatarData(it)
                name = it.getRemarkOrName()
            }
            tvName.text = name
            tvNumber.text = id
        }
    }

    private fun updatePresence(isRefreshAvatar:Boolean = false){
        ChatUIKitClient.getCurrentUser()?.let { user->
            val presence = PresenceCache.getUserPresence(user.id)
            presence?.let {
                if (isRefreshAvatar){
                    binding?.epPresence?.setUserAvatarData(user)
                }else{
                    binding?.epPresence?.setUserStatusData(EasePresenceUtil.getPresenceIcon(mContext,it))
                    binding?.epPresence?.getStatusView()?.visibility = View.VISIBLE
                    val subtitle = EasePresenceUtil.getPresenceString(mContext,it)
                    binding?.itemPresence?.setContent(subtitle)
                }
            }?:kotlin.run {
                binding?.epPresence?.setUserAvatarData(user)
            }
            binding?.tvName?.text = user.getNotEmptyName()
        }
    }

    private fun initStatus(){
        val isSilent = ChatUIKitClient.checkMutedConversationList(ChatClient.getInstance().currentUser)
        if (isSilent) {
            binding?.icNotice?.visibility = View.VISIBLE
        }else{
            binding?.icNotice?.visibility = View.GONE
        }
    }

    private fun logout() {
        lifecycleScope.launch {
            loginViewModel.logout()
                .catchChatException { e ->
                    ChatLog.e(TAG, "logout failed: ${e.description}")
                }
                .collect {
                    DemoHelper.getInstance().getDataModel().clearCache()
                    PresenceCache.clear()
                    DemoApplication.getInstance().getLifecycleCallbacks().skipToTarget(
                        LoginActivity::class.java)
                }
        }
    }

    private fun cancelAccount(){
        lifecycleScope.launch {
            loginViewModel.cancelAccount()
                .catchChatException {e ->
                    ChatLog.e(TAG, "cancelAccount failed: ${e.errorCode} ${e.description}")
                }
                .collect{
                    DemoHelper.getInstance().getDataModel().clearCache()
                    DemoApplication.getInstance().getLifecycleCallbacks().skipToTarget(
                        LoginActivity::class.java)
                }
        }
    }

    private fun fetchCurrentPresence(){
        presenceViewModel.fetchPresenceStatus(mutableListOf(ChatClient.getInstance().currentUser))
    }

    override fun onPresenceClick(v: View?) {

    }

    override fun onPresenceAvatarClick(v: View) {
        if (DeveloperModeHelper.isRequestToAppServer()){
            showSelectDialog()
        }else{
            mContext.mainScope().launch {
                mContext.showToast(mContext.getString(R.string.main_information_checked_model))
            }
        }
    }

    private fun showSelectDialog(){
        showSelectDialog = SimpleListSheetDialog(
            context = mContext,
            itemList = mutableListOf(
                ChatUIKitMenuItem(
                    menuId = R.id.about_information_camera,
                    title = getString(R.string.main_about_me_information_camera),
                    titleColor = ContextCompat.getColor(mContext, com.hyphenate.easeui.R.color.ease_color_primary)
                ),
                ChatUIKitMenuItem(
                    menuId = R.id.about_information_picture,
                    title = getString(R.string.main_about_me_information_picture),
                    titleColor = ContextCompat.getColor(mContext, com.hyphenate.easeui.R.color.ease_color_primary)
                )
            ),
            itemListener = object : SimpleListSheetItemClickListener {
                override fun onItemClickListener(position: Int, menu: ChatUIKitMenuItem) {
                    simpleMenuItemClickListener(menu)
                    showSelectDialog?.dismiss()
                }
            })
        this.parentFragmentManager.let { showSelectDialog?.show(it,"image_select_dialog") }
    }

    private val requestCameraPermission: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            onRequestResult(
                result,
                REQUEST_CODE_CAMERA
            )
        }

    private val requestImagePermission: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            onRequestResult(
                result,
                REQUEST_CODE_STORAGE_PICTURE
            )
        }

    private val launcherToCamera: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        result -> onActivityResult(result, RESULT_CODE_CAMERA)
    }
    private val launcherToAlbum: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        result -> onActivityResult(result, RESULT_CODE_LOCAL)
    }

    private val launcherToMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Callback is invoked after the user selects a media item or closes the
        if (uri != null) {
            ChatLog.d("launcherToMedia", "Selected URI: $uri")
            cameraAndCroppingController.gotoCrop(uri)
            val cropUri = cameraAndCroppingController.getImageCropUri()
            ChatLog.e(TAG, "-----------> corpUri: $cropUri")
            // FIXME:
            uploadFile(cropUri?.path)
        } else {
            ChatLog.d("launcherToMedia", "No media selected")
        }
    }

    /**
     * It's the result from ActivityResultLauncher.
     * @param result
     * @param requestCode
     */
    private fun onActivityResult(result: ActivityResult, requestCode: Int) {
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            when (requestCode) {
                RESULT_CODE_CAMERA -> { // capture new image
                    onActivityResultForCamera(data)
                }
                RESULT_CODE_LOCAL -> {
                    onActivityResultForLocalPhotos(data)
                }
            }
        }
    }

    private fun onActivityResultForCamera(data: Intent?) {
        val imageUri = cameraAndCroppingController.resultForCamera(data)
        val result = ChatImageUtils.checkDegreeAndRestoreImage(mContext,imageUri)
        this.imageUri = result
        imageUri?.let {
            cameraAndCroppingController.gotoCrop(it)
        }
    }

    private fun onActivityResultForLocalPhotos(data: Intent?) {
        if (data != null) {
            val selectedImage = data.data
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2){
                selectedImage?.let { cameraAndCroppingController.gotoCrop(it) }
            }else{
                if (selectedImage != null) {
                    var filePath: String = ChatUIKitFileUtils.getFilePath(mContext, selectedImage)
                    if (!TextUtils.isEmpty(filePath) && File(filePath).exists()) {
                        imageUri = Uri.parse(filePath)
                    } else {
                        imageUri = selectedImage
                        selectedImage.path?.let {
                            filePath = it
                        }
                    }
                    imageUri?.let { cameraAndCroppingController.gotoCrop(it) }
                }
            }
        }
    }

    /**
     * select local image
     */
    private fun selectPicFromLocal(launcher: ActivityResultLauncher<Intent>?) {
        ChatUIKitCompat.openImageByLauncher(launcher, mContext)
    }

    private fun onRequestResult(result: Map<String, Boolean>?, requestCode: Int) {
        if (!result.isNullOrEmpty()) {
            for ((key, value) in result) {
                ChatLog.e("UserInformationActivity", "onRequestResult: $key  $value")
            }
            if (PermissionCompat.getMediaAccess(mContext) !== PermissionCompat.StorageAccess.Denied) {
                if (requestCode == REQUEST_CODE_STORAGE_PICTURE) {
                    selectPicFromLocal(launcherToAlbum)
                }else if (requestCode == REQUEST_CODE_CAMERA){
                    cameraAndCroppingController.selectPicFromCamera(launcherToCamera)
                }else if (requestCode == REQUEST_CODE_LOCAL_EDIT){
                    imageUri?.let { cameraAndCroppingController.gotoCrop(it) }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        ChatLog.e(TAG, "----------> data: $data")
        super.onActivityResult(requestCode, resultCode, data)
        data?.let {
            if (resultCode == RESULT_OK && requestCode == UCrop.REQUEST_CROP) {
                val resultUri = UCrop.getOutput(data)
                resultUri?.let { uri ->
                    val result = ChatImageUtils.checkDegreeAndRestoreImage(mContext, uri)
                    imageUri = result
                    val path = CameraAndCropFileUtils.getAbsolutePathFromUri(mContext, result)
                    ChatLog.e("UserInformationActivity", "onActivityResult crop path $path")
                    path?.let {
                        // Uncomment this line to ensure path is logged.
                        ChatLog.e(TAG, "----------> path: $it")
                        // Uncomment this to upload file if needed.
                        // uploadFile(it)
                    }
                }
            } else if (resultCode == UCrop.RESULT_ERROR) {
                val cropError = UCrop.getError(data)
                ChatLog.e("UserInformationActivity", "onActivityResult crop error ${cropError?.message}")
            } else {
                // Handle other results if needed
            }
        }
    }

    fun simpleMenuItemClickListener(menu: ChatUIKitMenuItem){
        when(menu.menuId){
            R.id.about_information_camera -> {
                if (PermissionCompat.checkPermission(
                        mContext,
                        requestCameraPermission,
                        Manifest.permission.CAMERA,
                    )
                ) {
                    cameraAndCroppingController.selectPicFromCamera(launcherToCamera)
                }
                ChatLog.e(TAG, "-------------> Camera")
            }
            R.id.about_information_picture -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2){
                    val mimeType = "image/*"
                    launcherToMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.SingleMimeType(mimeType)))
                    ChatLog.e(TAG, "1 -------------> Picture")
                }else{
                    if (PermissionCompat.checkMediaPermission(
                            mContext,
                            requestImagePermission,
                            Manifest.permission.READ_MEDIA_IMAGES
                        )
                    ) {
                        selectPicFromLocal(launcherToAlbum)
                    }
                    ChatLog.e(TAG, "2 -------------> Picture")
                }
            }
            else -> {}
        }
    }

    private fun uploadFile(filePath:String?){
        ChatLog.e("UserInformationActivity","uploadFile filePath $filePath")
        val scaledImage = ChatImageUtils.getScaledImageByUri(mContext, filePath)
        lifecycleScope.launch {
            model.uploadAvatar(scaledImage)
                .onStart {
                    showLoading(true)
                }
                .onCompletion { dismissLoading() }
                .catchChatException { e ->
                    ChatLog.e("UserInformationActivity", "uploadAvatar fail error message = " + e.description)
                    mContext.mainScope().launch {
                        mContext.showToast("uploadFile error ${e.errorCode} ${e.description}")
                    }
                }
                .stateIn(lifecycleScope, SharingStarted.WhileSubscribed(5000), null)
                .collect {
                    it?.let {
                        updatePresence(true)
                    }
                }
        }
    }

    override fun onClick(v: View?) {
        when(v?.id){
            R.id.item_presence -> {
                ChatUIKitClient.getCurrentUser()?.id?.let {
                    presenceController.showPresenceStatusDialog(PresenceCache.getUserPresence(it))
                }
            }
            R.id.item_information -> {
                startActivity(Intent(mContext, UserInformationActivity::class.java))
            }
            R.id.item_currency -> {
                startActivity(Intent(mContext, CurrencyActivity::class.java))
            }
            R.id.item_notify -> {
                startActivity(Intent(mContext, NotifyActivity::class.java))
            }
            R.id.item_privacy -> {
                startActivity(Intent(mContext, ChatUIKitBlockListActivity::class.java))
            }
            R.id.item_about -> {
                var clazz:Class<*>?
                try {
                    clazz  = Class.forName("com.hyphenate.chatdemo.EMActivity")
                }catch (e:Exception){
                    clazz = Class.forName("com.hyphenate.chatdemo.ui.me.AboutActivity")
                }
                startActivity(Intent(mContext, clazz))
            }
            R.id.about_me_logout -> {
                showLogoutDialog()
            }
            R.id.about_me_account_cancellation -> {
                showCancelAccountDialog()
            }
            R.id.tv_number -> {
                val indexOfSpace = binding?.tvNumber?.text?.indexOf(":")
                indexOfSpace?.let {
                    if (indexOfSpace != -1) {
                        val substring = binding?.tvNumber?.text?.substring(indexOfSpace + 1)
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                null,
                                substring
                            )
                        )
                        mContext.showToast(resources.getString(R.string.copied))
                    }
                }

            }
            else -> {}
        }
    }

    private fun showLogoutDialog(){
        val logoutDialog = CustomDialog(
            context = mContext,
            title = resources.getString(R.string.em_login_out_hint),
            isEditTextMode = false,
            onLeftButtonClickListener = {

            },
            onRightButtonClickListener = {
                logout()
            }
        )
        logoutDialog.show()
    }

    private fun showCancelAccountDialog(){
        val cancelAccountDialog = CustomDialog(
            context = mContext,
            title = resources.getString(R.string.em_login_cancel_account_title),
            subtitle = resources.getString(R.string.em_login_cancel_account_subtitle),
            isEditTextMode = false,
            onLeftButtonClickListener = {

            },
            onRightButtonClickListener = {
                if (!DemoHelper.getInstance().getDataModel().isDeveloperMode()){
                    cancelAccount()
                }else{
                    mContext.showToast(R.string.main_account_cancellation)
                }
            }
        )
        cancelAccountDialog.show()
    }

    override fun fetchPresenceStatusSuccess(presence: MutableList<ChatPresence>) {
        updatePresence()
    }

    override fun fetchPresenceStatusFail(code: Int, message: String?) {

    }

}
package net.ankio.bluetooth.ui


import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ankio.bluetooth.BuildConfig
import net.ankio.bluetooth.R
import net.ankio.bluetooth.bluetooth.BleDevice
import net.ankio.bluetooth.databinding.AboutDialogBinding
import net.ankio.bluetooth.databinding.ActivityMainBinding
import net.ankio.bluetooth.databinding.InputLayoutBinding
import net.ankio.bluetooth.service.SendWebdavServer
import net.ankio.bluetooth.utils.BleConstant
import net.ankio.bluetooth.utils.HookUtils
import net.ankio.bluetooth.utils.SpUtils
import net.ankio.bluetooth.utils.WebdavUtils
import rikka.html.text.toHtml
import java.lang.Exception
import java.util.ArrayList


class MainActivity : BaseActivity() {

    //视图绑定
    private lateinit var binding: ActivityMainBinding
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tag = "MainActivity"
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        toolbarLayout = binding.toolbarLayout
        toolbar = binding.toolbar
        scrollView = binding.scrollView

        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.theme -> {
                    start<SettingsActivity>()
                    true
                }
                R.id.more -> {
                    val binding = AboutDialogBinding.inflate(LayoutInflater.from(this), null, false)
                    binding.sourceCode.movementMethod = LinkMovementMethod.getInstance()
                    binding.sourceCode.text = getString(
                        R.string.about_view_source_code,
                        "<b><a href=\"https://github.com/AnkioTomas/bluetooth\n\">GitHub</a></b>"
                    ).toHtml()

                    binding.versionName.text = packageManager.getPackageInfo(packageName, 0).versionName
                    MaterialAlertDialogBuilder(this)
                        .setView(binding.root)
                        .show()

                    true
                }
                else -> false
            }

        }
        binding.search.setOnClickListener {
            start<ScanActivity>()
        }
        onViewCreated()
    }

    /**
     * 判断是否为发送端
     */
    private fun isSender(): Boolean {
        return SpUtils.getBoolean("pref_enable_webdav", false) && SpUtils.getBoolean("pref_as_sender", false)
    }

    /**
     * 尝试去启动服务
     */
    private fun serverConnect(){
        if (isSender()) {
            startServer()
        }else{
            stopServer()
            if(SpUtils.getBoolean("pref_enable_webdav", false)){
               try {
                   syncFromServer()
               }catch (e:Exception){
                  Toast.makeText(this,e.message?:"",Toast.LENGTH_SHORT).show()
               }
            }
        }
        refreshStatus()
    }
    /**
     * 设置插件状态
     */
    private fun setActive(@StringRes text: Int, @AttrRes backgroundColor:Int, @AttrRes textColor:Int, @DrawableRes drawable:Int){
        binding.active.setBackgroundColor(getThemeAttrColor(backgroundColor))
        binding.imageView.setImageDrawable(
            AppCompatResources.getDrawable(
                this,
                drawable
            )
        )
        binding.msgLabel.text = getString(text)
        binding.imageView.setColorFilter(getThemeAttrColor(textColor))
        binding.msgLabel.setTextColor(getThemeAttrColor(textColor))
    }

    /**
     * 状态刷新
     */
    private fun refreshStatus(){
        //如果是发送端，判断服务状态
        if (isSender()) {
            Log.i(tag,"isServerRunning => ${SendWebdavServer.isRunning}")
            if(!SendWebdavServer.isRunning){//判断服务是否运行
                setActive(R.string.server_error,com.google.android.material.R.attr.colorErrorContainer,com.google.android.material.R.attr.colorOnErrorContainer, R.drawable.ic_error)
            }else{
                setActive(R.string.server_working,com.google.android.material.R.attr.colorPrimary,com.google.android.material.R.attr.colorOnPrimary,R.drawable.ic_success)
            }
        //其他情况就判断插件是否运行
        }else if (HookUtils.getActiveAndSupportFramework()) {
            if(HookUtils.getXposedVersion() < 93){
                setActive(R.string.active_version,com.google.android.material.R.attr.colorSecondary,com.google.android.material.R.attr.colorOnSecondary,R.drawable.ic_error)
                return
            }

            SpUtils.putInt("app_version",BuildConfig.VERSION_CODE)
            //其他情况就是激活
            setActive(R.string.active_success,com.google.android.material.R.attr.colorPrimary,com.google.android.material.R.attr.colorOnPrimary,R.drawable.ic_success)

        } else {
            //其他情况就是未激活
            setActive(R.string.active_error,com.google.android.material.R.attr.colorErrorContainer,com.google.android.material.R.attr.colorOnErrorContainer, R.drawable.ic_error)
        }
    }
    override fun onResume() {
        super.onResume()
        setMacBluetoothData()
        serverConnect()
    }

    /**
     * 启动服务
     */
    private fun startServer(){
        //如果服务没有启动先启动
        if(!SendWebdavServer.isRunning){
            SendWebdavServer.isRunning = true
            Log.i(tag,"bluetooth server start!")
            val intent = Intent(this, SendWebdavServer::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    /**
     * 停止服务
     */
    private fun stopServer(){
        if(SendWebdavServer.isRunning){
            Log.i(tag,"bluetooth server stop!")
            val intent = Intent(this, SendWebdavServer::class.java)
            this@MainActivity.stopService( intent)
        }
    }

    /**
     * 从服务端同步数据
     */
    private fun syncFromServer(){
        coroutineScope .launch(Dispatchers.IO) {
            try {
                // 在后台线程中执行网络操作
                val bluetoothData  = WebdavUtils(SpUtils.getString("webdav_username", ""),SpUtils.getString("webdav_password", "")).getFromServer()
                if(bluetoothData!=null){
                    //只更新data，mac地址
                    SpUtils.putString("pref_data",bluetoothData.data)
                    SpUtils.putString("pref_mac",bluetoothData.mac)
                }
                withContext(Dispatchers.Main) {
                    //同步完成数据后，重绘页面
                    setMacBluetoothData()
                }
            }catch (e: SardineException){
                e.message?.let { Log.e(tag, it) }
                withContext(Dispatchers.Main) {
                    showMsg(R.string.webdav_error)
                }
            }
        }
    }

    fun saveToLocal(){
        val historyJson = SpUtils.getString("history", "")
        var localHistoryList = Gson().fromJson(historyJson, object : TypeToken<List<BleDevice>>() {}.type)
                as? MutableList<BleDevice>
        if(localHistoryList==null){
            localHistoryList = ArrayList()
        }
       val pref_data =  SpUtils.getString("pref_data", "")
        val pref_mac =   SpUtils.getString("pref_mac", "")
        val pref_rssi =  SpUtils.getString("pref_rssi", "-50")
        var value = pref_rssi
        if(value==null||value=="")value="0"
        var insert = false
        var saveHistory = ArrayList<BleDevice>()
        localHistoryList.forEach {
            if(it.address == pref_mac){
                it.data = pref_data
                it.rssi = value.toInt()
                saveHistory.add(it)
                insert = true
            }else{
                saveHistory.add(it)
            }
        }
        if(!insert){
            saveHistory.add(BleDevice(pref_data,getString(R.string.manual_increase),value.toInt(),pref_mac,""))
        }
        SpUtils.putString("history",Gson().toJson(saveHistory))
    }

    /**
     * 设置页面
     */
    private fun setMacBluetoothData() {
        //蓝牙数据设置
        SpUtils.getString("pref_mac", "").apply {
            binding.macLabel.setText(this)
            binding.macLabel.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {

                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable) {
                    SpUtils.putString("pref_mac", s.toString())
                    saveToLocal()
                }
            })

        }
        SpUtils.getString("pref_data", "").apply {
            binding.broadcastLabel.setText(this)
            binding.broadcastLabel.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {

                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable) {
                    SpUtils.putString("pref_data", s.toString())
                    saveToLocal()
                }
            })

        }
        SpUtils.getString("pref_rssi", "-50").apply {
            var value = this
            if(value=="")value="0"
            binding.signalLabel.value = -value.toFloat()
            binding.tvRssi.text =  value + " dBm"
            binding.signalLabel.addOnChangeListener { _, value, _ ->
                binding.tvRssi.text = "-" + value.toInt().toString() + " dBm"
                SpUtils.putString("pref_rssi", "-" + value.toInt().toString())
                saveToLocal()
            }

        }

        //启用webdav
        SpUtils.getBoolean("pref_enable_webdav", false).apply {
            binding.webdavEnable.isChecked = this
            binding.webdavEnable.setOnCheckedChangeListener { _, isChecked ->
                SpUtils.putBoolean("pref_enable_webdav", isChecked)
                //启用webdav重连服务
                serverConnect()
            }
        }
        //是否作为发送端
        SpUtils.getBoolean("pref_as_sender", false).apply {
            binding.asSender.isChecked = this
            binding.asSender.setOnCheckedChangeListener { _, isChecked ->
                SpUtils.putBoolean("pref_as_sender", isChecked)
                if (isChecked) {
                    binding.enable.visibility = View.GONE
                    SpUtils.putBoolean("pref_enable", false)
                } else {
                    binding.enable.visibility = View.VISIBLE
                }
                serverConnect()
            }

        }
        //是否开启模拟
        SpUtils.getBoolean("pref_enable", false).apply {
            binding.switchButton.isChecked = this
            binding.switchButton.setOnCheckedChangeListener { _, isChecked ->
                SpUtils.putBoolean("pref_enable", isChecked)
            }
        }
        //配置信息
        SpUtils.getString("webdav_server", "https://dav.jianguoyun.com/dav/").apply {
            binding.webdavLabel.setText(this)
            binding.webdavLabel.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {

                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable) {
                    SpUtils.putString("webdav_server", s.toString())
                }
            })

        }
        SpUtils.getString("webdav_username", "").apply {
            binding.usernameLabel.setText(this)
            binding.usernameLabel.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {

                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable) {
                    SpUtils.putString("webdav_username", s.toString())
                }
            })

        }
        SpUtils.getString("webdav_password", "").apply {
            binding.passwordLabel.setText(this)
            binding.passwordLabel.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {

                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable) {
                    SpUtils.putString("webdav_password", s.toString())
                }
            })

        }
        SpUtils.getString("pref_company", "").apply {
            binding.companyLabel.setText(this)
            binding.companyLabel.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {

                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable) {
                    SpUtils.putString("pref_company", s.toString())
                }
            })

        }
        SpUtils.getString("pref_mac2", "").apply {
            binding.mac2Label.setText(this)
            binding.mac2Label.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {

                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable) {
                    SpUtils.putString("pref_mac2", s.toString())
                }
            })

        }

        SpUtils.getString("webdav_last", getString(R.string.webdav_no_sync)).apply {
            binding.lastDate.text = this
        }

    }

}
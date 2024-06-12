package net.ankio.bluetooth.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.marginBottom
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.chad.library.adapter4.BaseQuickAdapter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.permissionx.guolindev.PermissionX
import com.zackratos.ultimatebarx.ultimatebarx.navigationBarHeight
import kotlinx.coroutines.launch
import net.ankio.bluetooth.R
import net.ankio.bluetooth.adapter.BleDeviceAdapter
import net.ankio.bluetooth.bluetooth.BleDevice
import net.ankio.bluetooth.data.BluetoothData
import net.ankio.bluetooth.databinding.ActivityScanBinding
import net.ankio.bluetooth.ui.FilterFragment.FilterCloseInterface
import net.ankio.bluetooth.utils.*
import net.ankio.bluetooth.utils.BleConstant.BleConstant.COMPANY
import net.ankio.bluetooth.utils.BleConstant.BleConstant.RSSI
import java.util.*


/**
 * 扫描
 */
@SuppressWarnings("MissingPermission")
class ScanActivity : BaseActivity() {

    //视图绑定
    private lateinit var binding: ActivityScanBinding

    //默认蓝牙适配器
    private lateinit var defaultAdapter: BluetoothAdapter

    //低功耗蓝牙适配器
    private lateinit var bleAdapter: BleDeviceAdapter

    //蓝牙列表
    private var mList: MutableList<BleDevice> = ArrayList()

    //蓝牙列表
    private var historyList: MutableList<BleDevice> = ArrayList()

    //地址列表
    private var addressList: MutableList<String> = ArrayList()

    //当前是否扫描
    private var isScanning = false


    //当前扫描设备是否过滤设备信号值强度低于目标值的设备
    private var rssi = -100

    //注册开启蓝牙  注意在onCreate之前注册
    private val activityResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) showMsg(if (defaultAdapter.isEnabled) "蓝牙已打开" else "蓝牙未打开")
        }



    //扫描结果回调
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord?.bytes ?: return


            lifecycleScope.launch {
                val companyName = BluetoothData(this@ScanActivity).parseManufacturerData(scanRecord)
                //   val companyName = BluetoothCompanyParser(this@ScanActivity).getCompanyName(manufacturerId)
                val name = result.device.name

                //   name+= if(TextUtils.isEmpty(companyName))"None" else companyName
                addDeviceList(
                    BleDevice(
                        ByteUtils.bytesToHexString(scanRecord)?:"",
                        if (TextUtils.isEmpty(companyName)) "None" else companyName,
                        result.rssi,
                        result.device.address,
                        if (TextUtils.isEmpty(name)) "None" else name
                    )
                )
            }


        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityScanBinding.inflate(layoutInflater)
        toolbarLayout = binding.toolbarLayout
        toolbar = binding.toolbar
        scrollView = binding.scrollView
        setContentView(binding.root)
        onViewCreated()

        try {
            defaultAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            //检查权限
            requestPermission()
            //初始化页面
            initView()
        } catch (e: NullPointerException) {
            e.message?.let { Log.e(tag, it) }
            showMsg(getString(R.string.unsupported_bluetooth))
            finish()
        }

    }

    override fun onStop() {
        super.onStop()
        stopScan()
    }

    /**
     * 初始化
     */
    private fun initView() {
        val historyJson = SpUtils.getString("history", "")
        val localHistoryList = Gson().fromJson(historyJson, object : TypeToken<List<BleDevice>>() {}.type)
                as? MutableList<BleDevice> // 使用安全类型转换操作符as?
        historyList = localHistoryList?:mutableListOf()

      toolbar.setNavigationOnClickListener { finish(); }
        bleAdapter = BleDeviceAdapter(mList).apply {
            setOnItemClickListener { _, _, position ->
                val bleDevice = mList[position]
                SpUtils.putString("pref_mac", bleDevice.address)
                SpUtils.putString("pref_data", bleDevice.data)
                SpUtils.putString("pref_rssi", bleDevice.rssi.toString())
                if(!historyList.contains(bleDevice)){
                    historyList.add(bleDevice)
                    SpUtils.putString("history",Gson().toJson(historyList))
                }
                stopScan()
                startActivity(Intent(this@ScanActivity, MainActivity::class.java))
            }
            animationEnable = true
            setItemAnimation(BaseQuickAdapter.AnimationType.SlideInRight)
            setOnItemLongClickListener{ adapter, _, position ->
                if (mList!=historyList) return@setOnItemLongClickListener false
                adapter.notifyItemRemoved(position)
                mList.removeAt(position)
                historyList.removeAt(position)
                SpUtils.putString("history",Gson().toJson(historyList))
                true
            }
        }

        //扫描蓝牙
        binding.fabAdd.setOnClickListener {
            if (isScanning) stopScan()
            else scan()
        }
        toolbar.setOnMenuItemClickListener { it ->
            if(it.itemId == R.id.filter){
                val _scan = isScanning
                stopScan()


                FilterFragment().setOnCloseListener(object : FilterCloseInterface {
                    override fun onClose() {
                        if (_scan) scan()
                    }

                }).show(supportFragmentManager, "Filter")
            }else{
                stopScan()
                historyList.forEach { it2 ->
                    addDeviceList(it2,false)
                }
            }


            true

        }
        val linearLayoutManager = LinearLayoutManager(this)
        binding.scrollView.apply {
            layoutManager = linearLayoutManager
            adapter = bleAdapter
        }

         navigationBarHeight{
             val layoutParams = binding.fabAdd.layoutParams as ViewGroup.MarginLayoutParams
             layoutParams.bottomMargin = binding.fabAdd.marginBottom + it
             binding.fabAdd.layoutParams = layoutParams
        }
    }


    /**
     * 添加到设备列表
     */
    private fun addDeviceList(bleDevice: BleDevice,filter:Boolean = true) {
        if (filter){
            //过滤ble厂商名
            val company = SpUtils.getString(COMPANY, "")

            if (!TextUtils.isEmpty(company) && company.let { bleDevice.company?.contains(it) } == false) {
                return
            }
            rssi = -SpUtils.getInt(RSSI, 100)

            if (bleDevice.rssi < rssi) {
                return
            }
        }
        
        //检查之前所添加的设备地址是否存在当前地址列表
        val address = bleDevice.address
        if (!addressList.contains(address)) {
            addressList.add(address)
            mList.add(bleDevice)
            //刷新列表适配器
            bleAdapter.notifyItemInserted(mList.size)
        }
        //无设备UI展示
        binding.layNoEquipment.visibility = if (mList.size > 0) View.GONE else View.VISIBLE

    }


    /**
     * 请求权限
     */
    private fun requestPermission() {
        val arrayList = ArrayList<String>()
        arrayList.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayList.add(Manifest.permission.BLUETOOTH_SCAN)
            arrayList.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        PermissionX.init(this).permissions(arrayList)
            .request { allGranted, _, _ -> if (allGranted) openBluetooth() else showMsg(getString(R.string.no_permission)) }
    }


    /**
     * 打开蓝牙
     */
    private fun openBluetooth() = defaultAdapter.let {
        if (!it.isEnabled)
            activityResult.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            )
    }

    /**
     * 扫描蓝牙
     */
    private fun scan() {
        if (!::defaultAdapter.isInitialized || !defaultAdapter.isEnabled) {
            showMsg(getString(R.string.not_open_bluetooth));return
        }

        if (isScanning) {
            return
        }
        isScanning = true
        addressList.clear()
        mList.clear()



        try{
            defaultAdapter.bluetoothLeScanner.startScan(scanCallback)
        }catch (_:SecurityException){
            showMsg(R.string.no_permission);return;
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.fabAdd.text = getString(R.string.stop_scan)
        binding.fabAdd.icon = AppCompatResources.getDrawable(this, R.drawable.ic_bluetooth_close)
        Handler(Looper.getMainLooper()).postDelayed({ stopScan() }, 5 * 60 * 1000)
    }

    /**
     * 停止扫描
     */
    private fun stopScan() {
        addressList.clear()
        bleAdapter.notifyItemRangeRemoved(0, mList.size)
        mList.clear()
      
        if (!::defaultAdapter.isInitialized || !defaultAdapter.isEnabled) {
            showMsg(getString(R.string.not_open_bluetooth));return
        }
        if (isScanning) {
            isScanning = false
           try{
               defaultAdapter.bluetoothLeScanner.stopScan(scanCallback)
           }catch (_:SecurityException){
               showMsg(R.string.no_permission);return;
           }
            binding.progressBar.visibility = View.INVISIBLE
            binding.fabAdd.text = getString(R.string.start_scan)
            binding.fabAdd.icon = AppCompatResources.getDrawable(this, R.drawable.ic_bluetooth_scan)
            Handler(Looper.getMainLooper()).postDelayed({ stopScan() }, 60 * 1000)
        }
    }


    /**
     * Toast提示
     */
    private fun showMsg(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

}

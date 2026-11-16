package kr.smartscale.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * 체중계 BLE 연결 관리.
 * - autoConnect 로 연결해 두면 체중계에 올라가 기기가 켜질 때 자동 연결됨
 * - 알림(notify/indicate) 가능한 모든 특성을 구독해 데이터를 넘겨줌
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class ScaleBle(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onBleState(text: String, connected: Boolean)
        fun onBleData(uuid: String, data: ByteArray)
        fun onBleLog(msg: String)
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var wantConnected = false
    private val queue = ArrayDeque<BluetoothGattDescriptor>()

    var connected = false
        private set

    private fun state(text: String, conn: Boolean) {
        main.post { listener.onBleState(text, conn) }
    }

    private fun log(msg: String) {
        main.post { listener.onBleLog(msg) }
    }

    fun connect(addr: String) {
        val a = adapter
        if (a == null) {
            state("이 폰은 블루투스를 지원하지 않아요", false); return
        }
        if (!a.isEnabled) {
            state("블루투스가 꺼져 있어요", false); return
        }
        address = addr
        wantConnected = true
        try { gatt?.close() } catch (e: Exception) {}
        gatt = null
        val dev: BluetoothDevice = try {
            a.getRemoteDevice(addr)
        } catch (e: Exception) {
            log("주소 오류: ${e.message}"); return
        }
        state("대기 중 — 체중계에 올라가면 자동 연결돼요", false)
        log("연결 시도 $addr")
        gatt = dev.connectGatt(ctx, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        wantConnected = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {}
        gatt = null
        connected = false
        state("연결 해제됨", false)
    }

    private fun writeNext(g: BluetoothGatt) {
        val d = queue.removeFirstOrNull()
        if (d == null) {
            state("✅ 연결됨 — 측정 준비 완료", true)
            return
        }
        if (!g.writeDescriptor(d)) {
            log("구독 요청 실패 ${d.characteristic.uuid}")
            main.postDelayed({ writeNext(g) }, 100)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                log("연결됨 (status=$status)")
                state("연결됨 — 서비스 확인 중…", true)
                main.postDelayed({ g.discoverServices() }, 300)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                log("연결 끊김 (status=$status)")
                if (wantConnected) {
                    state("대기 중 — 체중계에 올라가면 자동 연결돼요", false)
                    main.postDelayed({
                        val a = address
                        if (wantConnected && a != null) connect(a)
                    }, 1500)
                } else {
                    state("연결 해제됨", false)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            queue.clear()
            for (s in g.services) {
                log("서비스 ${s.uuid}")
                for (c in s.characteristics) {
                    val p = c.properties
                    val props = ArrayList<String>()
                    if ((p and BluetoothGattCharacteristic.PROPERTY_READ) != 0) props.add("read")
                    if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) props.add("write")
                    if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) props.add("writeNR")
                    if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) props.add("notify")
                    if ((p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) props.add("indicate")
                    log("  특성 ${c.uuid} [${props.joinToString(",")}]")
                    val canNotify = (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val canIndicate = (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    if (canNotify || canIndicate) {
                        g.setCharacteristicNotification(c, true)
                        val d = c.getDescriptor(CCCD)
                        if (d != null) {
                            d.value = if (canNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            queue.add(d)
                        }
                    }
                }
            }
            main.post { writeNext(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("  → 구독 ${d.characteristic.uuid.toString().substring(4, 8)} (status=$status)")
            main.post { writeNext(g) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            val copy = v.copyOf()
            val u = c.uuid.toString()
            main.post { listener.onBleData(u, copy) }
        }
    }
}

package kr.smartscale.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

/**
 * Firebase Firestore 로 가족 간 기록·프로필 동기화.
 * 경로: families/{가족코드} (profiles 필드) / records/{rid}
 * google-services.json 이 없거나 가족 코드가 없으면 아무 일도 하지 않음.
 */
object Sync {
    var active = false
        private set
    var lastError = ""
        private set
    private var fam: DocumentReference? = null
    private var recReg: ListenerRegistration? = null
    private var famReg: ListenerRegistration? = null

    fun firebaseReady(ctx: Context): Boolean = try {
        FirebaseApp.getApps(ctx).isNotEmpty() || FirebaseApp.initializeApp(ctx) != null
    } catch (e: Exception) {
        false
    }

    fun stop() {
        recReg?.remove(); famReg?.remove()
        recReg = null; famReg = null; fam = null
        active = false
    }

    fun start(ctx: Context) {
        stop()
        val code = Hub.prefs.familyCode
        if (code.length < 20) return
        if (!firebaseReady(ctx)) { lastError = "Firebase 설정 파일이 아직 없어요"; return }
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) listen(code)
        else auth.signInAnonymously()
            .addOnSuccessListener { listen(code) }
            .addOnFailureListener {
                lastError = if ((it.message ?: "").contains("administrators"))
                    "Firebase에서 익명 로그인이 꺼져 있어요 (Authentication → 로그인 방법 → 익명 사용 설정)"
                else "로그인 실패: ${it.message}"
                Hub.addLog("동기화 " + lastError)
                Hub.emit(Hub.EV_STATE)
            }
    }

    private fun listen(code: String) {
        val f = FirebaseFirestore.getInstance().collection("families").document(code)
        fam = f
        active = true
        lastError = ""
        Hub.addLog("동기화 시작")

        famReg = f.addSnapshotListener { snap, err ->
            if (err != null) { onError(err); return@addSnapshotListener }
            val json = snap?.getString("profiles")
            if (json == null) {
                pushProfiles(Hub.store.raw())     // 처음 만든 가족: 내 프로필 올리기
            } else if (json != Hub.store.raw()) {
                Hub.store.saveRaw(json)
                Hub.emit(Hub.EV_DATA)
            }
        }

        recReg = f.collection("records").addSnapshotListener { snap, err ->
            if (err != null) { onError(err); return@addSnapshotListener }
            if (snap == null) return@addSnapshotListener
            reauthTried = false
            var changed = false
            val remoteIds = HashSet<String>()
            for (ch in snap.documentChanges) {
                val d = ch.document
                val rid = d.id
                remoteIds.add(rid)
                when (ch.type) {
                    DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                        val uid = (d.getLong("uid") ?: 1L).toInt()
                        val local = Hub.db.byRid(rid)
                        if (local == null) {
                            val r = Hub.db.insert(
                                d.getLong("ts") ?: 0L, d.getDouble("weight") ?: 0.0,
                                (d.getLong("res") ?: 0L).toInt(), d.getDouble("sfat"),
                                d.getString("src") ?: "체중계", uid, rid
                            )
                            changed = true
                            maybeNotify(r, d.getString("dev") ?: "")
                        } else if (local.uid != uid) {
                            Hub.db.setUid(local.id, uid)
                            changed = true
                            maybeNotify(local.copy(uid = uid), d.getString("dev") ?: "")
                        }
                    }
                    DocumentChange.Type.REMOVED -> {
                        Hub.db.deleteByRid(rid)
                        changed = true
                    }
                }
            }
            // 이 폰에만 있는 기록(동기화 전 기록) 올리기 — 첫 수신 때 한 번
            if (!snap.metadata.isFromCache && !uploadedLocal) {
                uploadedLocal = true
                val have = snap.documents.map { it.id }.toHashSet()
                for (r in Hub.db.all()) if (r.rid.isNotEmpty() && !have.contains(r.rid)) upload(r)
            }
            if (changed) Hub.emit(Hub.EV_DATA)
        }
    }

    private var uploadedLocal = false
    private var reauthTried = false

    /** 인증이 무효가 되면(익명 계정 삭제 등) 한 번 새로 로그인해서 다시 연결 */
    private fun onError(err: com.google.firebase.firestore.FirebaseFirestoreException) {
        lastError = err.message ?: "오류"
        Hub.addLog("동기화 오류: ${err.code} $lastError")
        val code = err.code
        val authProblem = code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED ||
            code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
        if (authProblem && !reauthTried) {
            reauthTried = true
            stop()
            val auth = FirebaseAuth.getInstance()
            auth.signOut()
            auth.signInAnonymously()
                .addOnSuccessListener { Hub.addLog("다시 로그인함"); start(Hub.app) }
                .addOnFailureListener { lastError = "로그인 실패: ${it.message}"; Hub.emit(Hub.EV_STATE) }
            return
        }
        if (code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED)
            lastError = "접근이 거부됐어요. Firestore 규칙이 게시됐는지 확인해 주세요"
        Hub.emit(Hub.EV_STATE)
    }

    /** 다른 폰에서 측정된 내 기록이면 알림 */
    private fun maybeNotify(r: Rec, dev: String) {
        if (dev == Hub.prefs.deviceId) return
        if (r.uid != Hub.prefs.ownerUid) return
        if (System.currentTimeMillis() - r.ts > 30 * 60_000L) return
        if (Hub.activityVisible) return
        val p = Hub.store.get(r.uid) ?: return
        Notifier.measured(Hub.app, r, p)
    }

    fun upload(r: Rec) {
        val f = fam ?: return
        if (r.rid.isEmpty()) return
        val data = hashMapOf<String, Any?>(
            "ts" to r.ts, "weight" to r.weight, "res" to r.res, "sfat" to r.sfat,
            "src" to r.src, "uid" to r.uid, "dev" to Hub.prefs.deviceId
        )
        f.collection("records").document(r.rid).set(data)
    }

    fun setUid(rid: String, uid: Int) {
        val f = fam ?: return
        if (rid.isEmpty()) return
        f.collection("records").document(rid)
            .set(hashMapOf<String, Any>("uid" to uid, "dev" to Hub.prefs.deviceId), SetOptions.merge())
    }

    fun delete(rid: String) {
        val f = fam ?: return
        if (rid.isEmpty()) return
        f.collection("records").document(rid).delete()
    }

    fun pushProfiles(json: String) {
        val f = fam ?: return
        f.set(hashMapOf<String, Any>("profiles" to json, "updated" to System.currentTimeMillis()), SetOptions.merge())
    }

    /** 새 가족 코드 (추측하기 어려운 24자) */
    fun newCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = java.security.SecureRandom()
        return (1..24).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}

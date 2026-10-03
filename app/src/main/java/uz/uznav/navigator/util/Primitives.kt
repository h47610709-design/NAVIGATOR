package uz.uznav.navigator.util

/**
 * Marshrut hisoblashda millionlab element bilan ishlanadi, shuning uchun
 * bu yerda "boxing" qilmaydigan (ya'ni ortiqcha xotira sarflamaydigan)
 * sodda ma'lumot tuzilmalari yozilgan.
 */

/**
 * Long -> Int xaritasi (ochiq adreslash usuli).
 * Xarita tugunlarini (koordinata kalitidan tugun indeksiga) tez topish uchun.
 */
class LongIntMap(initialCapacity: Int = 1 shl 16) {

    private var keys: LongArray
    private var values: IntArray
    private var used: BooleanArray
    private var mask: Int
    private var size: Int = 0
    private var threshold: Int

    init {
        var cap = 16
        while (cap < initialCapacity) cap = cap shl 1
        keys = LongArray(cap)
        values = IntArray(cap)
        used = BooleanArray(cap)
        mask = cap - 1
        threshold = (cap * 0.65f).toInt()
    }

    val count: Int get() = size

    private fun indexFor(key: Long): Int {
        // Fibonacci-aralashtirish — kalitlarni tekis taqsimlaydi
        var h = (key * -0x61c8864680b583ebL)
        h = h xor (h ushr 32)
        return (h.toInt()) and mask
    }

    /** Kalit bo'yicha qiymatni qaytaradi, topilmasa [defaultValue]. */
    fun get(key: Long, defaultValue: Int = -1): Int {
        var i = indexFor(key)
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return defaultValue
    }

    /** Qiymatni yozadi (mavjud bo'lsa yangilaydi). */
    fun put(key: Long, value: Int) {
        var i = indexFor(key)
        while (used[i]) {
            if (keys[i] == key) {
                values[i] = value
                return
            }
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = key
        values[i] = value
        size++
        if (size > threshold) rehash()
    }

    private fun rehash() {
        val oldKeys = keys
        val oldValues = values
        val oldUsed = used
        val newCap = keys.size shl 1
        keys = LongArray(newCap)
        values = IntArray(newCap)
        used = BooleanArray(newCap)
        mask = newCap - 1
        threshold = (newCap * 0.65f).toInt()
        size = 0
        for (j in oldKeys.indices) {
            if (oldUsed[j]) put(oldKeys[j], oldValues[j])
        }
    }

    fun clear() {
        java.util.Arrays.fill(used, false)
        size = 0
    }
}

/**
 * O'suvchi int massiv (ArrayList<Int> o'rniga, boxing bo'lmaydi).
 */
class IntList(initialCapacity: Int = 1024) {
    var data: IntArray = IntArray(if (initialCapacity < 4) 4 else initialCapacity)
        private set
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == data.size) grow()
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]

    operator fun set(index: Int, value: Int) {
        data[index] = value
    }

    private fun grow() {
        data = data.copyOf(if (data.size < 1_000_000) data.size * 2 else (data.size * 3) / 2)
    }

    fun toArray(): IntArray = data.copyOf(size)

    fun clear() {
        size = 0
    }
}

/** O'suvchi float massiv. */
class FloatList(initialCapacity: Int = 1024) {
    var data: FloatArray = FloatArray(if (initialCapacity < 4) 4 else initialCapacity)
        private set
    var size: Int = 0
        private set

    fun add(value: Float) {
        if (size == data.size) {
            data = data.copyOf(if (data.size < 1_000_000) data.size * 2 else (data.size * 3) / 2)
        }
        data[size++] = value
    }

    operator fun get(index: Int): Float = data[index]

    fun toArray(): FloatArray = data.copyOf(size)
}

/**
 * Minimal binar uyum (min-heap): A* algoritmida navbat sifatida ishlatiladi.
 * Takroriy elementlarga ruxsat beradi ("lazy deletion"), chunki yopilgan
 * tugunlar algoritmda alohida tekshiriladi.
 */
class MinHeap(initialCapacity: Int = 1024) {

    private var keys = DoubleArray(initialCapacity)
    private var values = IntArray(initialCapacity)
    var size: Int = 0
        private set

    val isEmpty: Boolean get() = size == 0

    fun clear() {
        size = 0
    }

    fun push(key: Double, value: Int) {
        if (size == keys.size) {
            keys = keys.copyOf(keys.size * 2)
            values = values.copyOf(values.size * 2)
        }
        var i = size++
        keys[i] = key
        values[i] = value
        // yuqoriga ko'tarish
        while (i > 0) {
            val parent = (i - 1) ushr 1
            if (keys[parent] <= keys[i]) break
            swap(i, parent)
            i = parent
        }
    }

    /** Eng kichik kalitli elementning qiymatini olib tashlab qaytaradi. */
    fun pop(): Int {
        val top = values[0]
        size--
        if (size > 0) {
            keys[0] = keys[size]
            values[0] = values[size]
            var i = 0
            while (true) {
                val left = 2 * i + 1
                val right = left + 1
                var smallest = i
                if (left < size && keys[left] < keys[smallest]) smallest = left
                if (right < size && keys[right] < keys[smallest]) smallest = right
                if (smallest == i) break
                swap(i, smallest)
                i = smallest
            }
        }
        return top
    }

    private fun swap(a: Int, b: Int) {
        val k = keys[a]; keys[a] = keys[b]; keys[b] = k
        val v = values[a]; values[a] = values[b]; values[b] = v
    }
}

package org.mycelium.library.vfs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.NoSuchFileException
import org.json.JSONException
import org.json.JSONObject

// Handles Electron's Asar format: a flat file consisting of a Chromium-"pickle"-framed JSON
// directory tree followed by the concatenated bytes of every file it describes. Asar has no
// magic bytes, so canOpen has to speculatively parse the pickle framing and the JSON header
// to decide whether this file is plausibly an asar archive at all — see the header format
// notes on parseHeader below (verified against electron/asar's src/pickle.ts and
// src/disk.ts, since the framing isn't otherwise documented anywhere authoritative).
//
// Header layout on disk:
//   [0:4)  UInt32LE  outer pickle payload size — always 4 (one UInt32 field)
//   [4:8)  UInt32LE  `headerSize` — byte length of the inner pickle that follows
//   [8:8+headerSize)          the inner pickle, itself framed as:
//     [0:4)              UInt32LE  inner pickle payload size
//     [4:8)              UInt32LE  `strLen` — byte length of the JSON string
//     [8:8+strLen)        UTF-8 JSON header string (no trailing padding accounted for
//                          here; the inner pickle's payload is padded to a 4-byte
//                          boundary but the string's own declared length is exact)
//   [8+headerSize:)  concatenated file data; a given file's bytes start at
//                     8 + headerSize + <that file's `offset`, parsed as a decimal string>
class AsarVfsProvider : VfsProvider {
    override fun canOpen(channel: SeekableByteChannel): Boolean {
        return try {
            parseHeader(channel) != null
        } catch (e: Exception) {
            false
        }
    }

    override fun list(channel: SeekableByteChannel, internalPath: String): List<VfsEntry> {
        val header = parseHeader(channel) ?: return emptyList()
        val dir = navigateToDirectory(header.json, internalPath) ?: return emptyList()
        val files = dir.optJSONObject("files") ?: return emptyList()
        return files.keySet().map { name ->
            val entry = files.getJSONObject(name)
            VfsEntry(name, isDirectory = entry.has("files"))
        }
    }

    override fun read(channel: SeekableByteChannel, internalPath: String): ByteArray {
        val header = parseHeader(channel) ?: throw NoSuchFileException(internalPath)
        val entry = navigateToEntry(header.json, internalPath) ?: throw NoSuchFileException(internalPath)
        if (entry.has("files")) throw NoSuchFileException(internalPath)
        val offset = entry.getString("offset").toLong()
        val size = entry.getLong("size")
        val base = 8L + header.headerSize
        channel.position(base + offset)
        val buffer = ByteBuffer.allocate(size.toInt())
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw NoSuchFileException(internalPath)
        }
        return buffer.array()
    }

    override fun attributes(channel: SeekableByteChannel, internalPath: String): VfsEntryAttributes? {
        val header = parseHeader(channel) ?: return null
        val target = normalize(internalPath)
        if (target.isEmpty()) return VfsEntryAttributes(isDirectory = true, size = 0, lastModified = 0)
        val entry = navigateToEntry(header.json, internalPath) ?: return null
        return if (entry.has("files")) {
            VfsEntryAttributes(isDirectory = true, size = 0, lastModified = 0)
        } else {
            VfsEntryAttributes(isDirectory = false, size = entry.getLong("size"), lastModified = 0)
        }
    }

    private fun navigateToEntry(root: JSONObject, internalPath: String): JSONObject? {
        val target = normalize(internalPath)
        if (target.isEmpty()) return root
        var current = root
        val segments = target.split('/')
        for ((index, segment) in segments.withIndex()) {
            val files = current.optJSONObject("files") ?: return null
            val next = files.optJSONObject(segment) ?: return null
            if (index == segments.lastIndex) return next
            current = next
        }
        return null
    }

    private fun navigateToDirectory(root: JSONObject, internalPath: String): JSONObject? {
        val entry = navigateToEntry(root, internalPath) ?: return null
        return if (entry === root || entry.has("files")) entry else null
    }

    private fun normalize(internalPath: String): String = internalPath.trim('/')

    private class Header(val json: JSONObject, val headerSize: Long)

    private fun parseHeader(channel: SeekableByteChannel): Header? {
        val originalPosition = channel.position()
        try {
            val archiveSize = channel.size()
            if (archiveSize < 8) return null

            channel.position(0)
            val outer = readFully(channel, 8) ?: return null
            val outerBuf = ByteBuffer.wrap(outer).order(ByteOrder.LITTLE_ENDIAN)
            val outerPayloadSize = outerBuf.getInt(0).toLong() and 0xFFFFFFFFL
            if (outerPayloadSize != 4L) return null
            val headerSize = outerBuf.getInt(4).toLong() and 0xFFFFFFFFL
            if (headerSize < 8 || headerSize > archiveSize - 8) return null

            val inner = readFully(channel, headerSize.toInt()) ?: return null
            val innerBuf = ByteBuffer.wrap(inner).order(ByteOrder.LITTLE_ENDIAN)
            val strLen = innerBuf.getInt(4).toLong() and 0xFFFFFFFFL
            if (strLen < 0 || 8 + strLen > inner.size) return null

            val json = String(inner, 8, strLen.toInt(), StandardCharsets.UTF_8)
            val root = JSONObject(json)
            if (!root.has("files")) return null

            return Header(root, headerSize)
        } catch (e: JSONException) {
            return null
        } catch (e: IndexOutOfBoundsException) {
            return null
        } finally {
            channel.position(originalPosition)
        }
    }

    private fun readFully(channel: SeekableByteChannel, length: Int): ByteArray? {
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) return null
        }
        return buffer.array()
    }
}

package com.example.fileprotocol

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FilesAdapter(
    private var files: List<HotspotFileServer.SharedFile> = emptyList(),
    private val onDownloadClick: (HotspotFileServer.SharedFile) -> Unit,
) : RecyclerView.Adapter<FilesAdapter.FileViewHolder>() {

    fun updateFiles(newFiles: List<HotspotFileServer.SharedFile>) {
        files = newFiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            android.R.layout.simple_list_item_2, parent, false,
        )
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        holder.text1.text = file.displayName
        holder.text2.text = "${formatSize(file.size)} • ${file.mimeType}"
        holder.itemView.setOnClickListener { onDownloadClick(file) }
    }

    override fun getItemCount(): Int = files.size

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text1: TextView = itemView.findViewById(android.R.id.text1)
        val text2: TextView = itemView.findViewById(android.R.id.text2)
    }

    private fun formatSize(size: Long): String = when {
        size < 1_024 -> "$size B"
        size < 1_048_576 -> "${size / 1_024} KB"
        else -> String.format("%.1f MB", size / 1_048_576.0)
    }
}

class TextsAdapter(
    private var snippets: List<HotspotFileServer.TextSnippet> = emptyList(),
    private val onCopyClick: (String) -> Unit,
) : RecyclerView.Adapter<TextsAdapter.TextViewHolder>() {

    fun updateTexts(newSnippets: List<HotspotFileServer.TextSnippet>) {
        snippets = newSnippets
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            android.R.layout.simple_list_item_2, parent, false,
        )
        return TextViewHolder(view)
    }

    override fun onBindViewHolder(holder: TextViewHolder, position: Int) {
        val snippet = snippets[position]
        holder.text1.text = snippet.text
        holder.text2.text = "Tap to copy to clipboard"
        holder.itemView.setOnClickListener { onCopyClick(snippet.text) }
    }

    override fun getItemCount(): Int = snippets.size

    class TextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text1: TextView = itemView.findViewById(android.R.id.text1)
        val text2: TextView = itemView.findViewById(android.R.id.text2)
    }
}

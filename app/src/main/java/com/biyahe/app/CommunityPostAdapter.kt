package com.biyahe.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

data class CommunityPost(
    val id: Int,
    val avatarInitials: String,
    val username: String,
    val timestamp: String,
    val tagText: String,
    val tagColorHex: String,
    val bodyText: String,
    val locationText: String,
    var helpfulCount: Int,
    var isHelpfulClicked: Boolean = false
)

class CommunityPostAdapter(
    private val posts: MutableList<CommunityPost>
) : RecyclerView.Adapter<CommunityPostAdapter.ViewHolder>() {

    fun addPost(post: CommunityPost) {
        posts.add(0, post)
        notifyItemInserted(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_community_post, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(posts[position])
    }

    override fun getItemCount(): Int = posts.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAvatar = itemView.findViewById<TextView>(R.id.tvPostUserAvatar)
        private val tvName = itemView.findViewById<TextView>(R.id.tvPostUserName)
        private val tvTime = itemView.findViewById<TextView>(R.id.tvPostTimestamp)
        private val tvTag = itemView.findViewById<TextView>(R.id.tvPostTag)
        private val tvBody = itemView.findViewById<TextView>(R.id.tvPostBody)
        private val tvLocation = itemView.findViewById<TextView>(R.id.tvPostLocation)
        private val btnHelpful = itemView.findViewById<TextView>(R.id.btnHelpful)

        fun bind(post: CommunityPost) {
            tvAvatar.text = post.avatarInitials
            tvName.text = post.username
            tvTime.text = "· ${post.timestamp}"
            tvTag.text = post.tagText
            tvTag.setTextColor(Color.parseColor(post.tagColorHex))
            tvBody.text = post.bodyText
            tvLocation.text = post.locationText

            updateHelpfulButton(post)

            btnHelpful.setOnClickListener {
                if (!post.isHelpfulClicked) {
                    post.helpfulCount++
                    post.isHelpfulClicked = true
                } else {
                    post.helpfulCount--
                    post.isHelpfulClicked = false
                }
                updateHelpfulButton(post)
            }
        }

        private fun updateHelpfulButton(post: CommunityPost) {
            btnHelpful.text = "👍 ${post.helpfulCount} Helpful"
            if (post.isHelpfulClicked) {
                btnHelpful.setTextColor(ContextCompat.getColor(itemView.context, R.color.ph_blue))
            } else {
                btnHelpful.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_secondary))
            }
        }
    }
}

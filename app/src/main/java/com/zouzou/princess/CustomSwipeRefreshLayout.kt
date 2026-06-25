package com.zouzou.princess

import android.content.Context
import android.util.AttributeSet
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * 自定义 SwipeRefreshLayout
 *
 * 重写 canChildScrollUp()，由 JS 回调设置是否可上滚，
 * 解决网页内部 div 滚动时 WebView.canScrollVertically 始终返回 false 的问题。
 */
class CustomSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    var webCanScrollUp: Boolean = false

    override fun canChildScrollUp(): Boolean {
        return webCanScrollUp
    }
}

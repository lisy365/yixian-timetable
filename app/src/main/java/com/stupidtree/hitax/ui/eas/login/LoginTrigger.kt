package com.stupidtree.hitax.ui.eas.login

import com.stupidtree.component.data.Trigger
import com.stupidtree.hitax.data.source.web.sysu.SysuSession

class LoginTrigger : Trigger() {
    var cookies: Map<String, String> = emptyMap()
    var info: SysuSession.Info? = null

    companion object {
        fun getActioning(cookies: Map<String, String>, info: SysuSession.Info?): LoginTrigger {
            val r = LoginTrigger()
            r.cookies = cookies
            r.info = info
            r.setActioning()
            return r
        }
    }
}

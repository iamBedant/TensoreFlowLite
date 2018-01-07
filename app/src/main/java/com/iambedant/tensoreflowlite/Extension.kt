package com.iambedant.tensoreflowlite

import android.app.Activity
import android.content.Context
import android.support.annotation.IdRes
import android.view.View
import android.widget.Toast

/**
 * Created by @iamBedant on 06/01/18.
 */


fun Any.toast(context: Context) {
    Toast.makeText(context, this.toString(), Toast.LENGTH_LONG).show()
}

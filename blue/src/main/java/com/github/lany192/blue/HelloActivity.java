package com.github.lany192.blue;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.alibaba.android.arouter.facade.annotation.Route;
import com.alibaba.android.arouter.launcher.ARouter;
import com.github.lany192.common.GreenPaths;

@Route(path = "/blue/hello")
public class HelloActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hello_blue);
        findViewById(R.id.textView).setOnClickListener(v -> ARouter.getInstance().build(GreenPaths.GREEN_HELLO).navigation());
    }
}
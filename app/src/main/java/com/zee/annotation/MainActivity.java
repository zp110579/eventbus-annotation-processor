package com.zee.annotation;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import org.greenrobot.eventbus.SubscribeSimple;
import org.greenrobot.eventbus.SubscribeTag;


@SubscribeTag(tag="mainActivity")
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
    @SubscribeSimple("ok--->>")
    public  void createView(){

    }
}
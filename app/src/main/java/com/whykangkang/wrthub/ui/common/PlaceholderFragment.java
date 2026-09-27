package com.whykangkang.wrthub.ui.common;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.whykangkang.wrthub.R;

/** 尚未实现页面的通用占位:大标题 + "功能开发中" */
public abstract class PlaceholderFragment extends Fragment {

    @StringRes
    protected abstract int titleRes();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_placeholder, container, false);
        ((TextView) root.findViewById(R.id.page_title)).setText(titleRes());
        return root;
    }
}
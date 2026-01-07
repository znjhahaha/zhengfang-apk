#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
抢课助手管理工具 - GUI 版本 (Gitee 同步)
支持：设备白名单管理 + 公告管理
"""

import json
import os
import base64
import tkinter as tk
from tkinter import ttk, messagebox
from datetime import datetime, timedelta
import urllib.request
import urllib.error

# ===== 配置 =====
GITEE_TOKEN = "1dfb6ac6653918d19e69aa0bbb43783e"
GITEE_OWNER = "znj12345"
GITEE_REPO = "zhengfang"
GITEE_BRANCH = "main"


def get_gitee_api_url(file_path):
    return f"https://gitee.com/api/v5/repos/{GITEE_OWNER}/{GITEE_REPO}/contents/{file_path}"


class GiteeHelper:
    """Gitee API 封装"""
    
    @staticmethod
    def load_file(file_path):
        """从 Gitee 加载文件，返回 (content_dict, sha)"""
        try:
            url = f"{get_gitee_api_url(file_path)}?access_token={GITEE_TOKEN}&ref={GITEE_BRANCH}"
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=10) as response:
                result = json.loads(response.read().decode('utf-8'))
                
                # 如果返回的是列表（目录内容），说明路径不对
                if isinstance(result, list):
                    return None, None
                
                sha = result.get('sha')
                content_b64 = result.get('content', '')
                content = base64.b64decode(content_b64).decode('utf-8')
                parsed = json.loads(content)
                
                # 确保返回的是字典
                if not isinstance(parsed, dict):
                    return {"version": 1, "devices": []}, sha
                
                return parsed, sha
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None, None
            raise
        except Exception as e:
            raise e
    
    @staticmethod
    def save_file(file_path, data, sha=None, message="更新文件"):
        """保存文件到 Gitee，返回新的 sha"""
        content = json.dumps(data, ensure_ascii=False, indent=2)
        content_b64 = base64.b64encode(content.encode('utf-8')).decode('utf-8')
        
        req_data = {
            "access_token": GITEE_TOKEN,
            "content": content_b64,
            "message": f"{message} - {datetime.now().strftime('%Y-%m-%d %H:%M')}"
        }
        
        if sha:
            req_data["sha"] = sha
        
        data_bytes = json.dumps(req_data).encode('utf-8')
        
        req = urllib.request.Request(
            get_gitee_api_url(file_path),
            data=data_bytes,
            method='PUT' if sha else 'POST',
            headers={'Content-Type': 'application/json'}
        )
        
        with urllib.request.urlopen(req, timeout=10) as response:
            result = json.loads(response.read().decode('utf-8'))
            return result.get('content', {}).get('sha')


class WhitelistTab:
    """设备白名单管理标签页"""
    
    def __init__(self, parent, status_callback):
        self.parent = parent
        self.status_callback = status_callback
        self.file_sha = None
        self.whitelist_data = {"version": 1, "devices": []}
        
        self.create_widgets()
        self.load_from_gitee()
    
    def create_widgets(self):
        # 添加设备区域
        add_frame = tk.LabelFrame(
            self.parent, 
            text=" ➕ 添加新设备 ", 
            font=('微软雅黑', 10, 'bold'),
            fg='#333',
            bg='#f5f5f5',
            padx=10,
            pady=8
        )
        add_frame.pack(fill=tk.X, padx=10, pady=10)
        
        input_frame = tk.Frame(add_frame, bg='#f5f5f5')
        input_frame.pack(fill=tk.X)
        
        tk.Label(input_frame, text="设备ID:", font=('微软雅黑', 9), bg='#f5f5f5').grid(row=0, column=0, padx=3, pady=3)
        self.device_id_entry = ttk.Entry(input_frame, width=12, font=('Consolas', 10))
        self.device_id_entry.grid(row=0, column=1, padx=3, pady=3)
        
        tk.Label(input_frame, text="过期:", font=('微软雅黑', 9), bg='#f5f5f5').grid(row=0, column=2, padx=3, pady=3)
        self.expire_entry = ttk.Entry(input_frame, width=10, font=('微软雅黑', 9))
        self.expire_entry.grid(row=0, column=3, padx=3, pady=3)
        self.expire_entry.insert(0, (datetime.now() + timedelta(days=30)).strftime("%Y-%m-%d"))
        
        tk.Label(input_frame, text="备注:", font=('微软雅黑', 9), bg='#f5f5f5').grid(row=0, column=4, padx=3, pady=3)
        self.note_entry = ttk.Entry(input_frame, width=15, font=('微软雅黑', 9))
        self.note_entry.grid(row=0, column=5, padx=3, pady=3)
        
        add_btn = tk.Button(input_frame, text="添加", command=self.add_device,
            font=('微软雅黑', 9, 'bold'), bg='#4CAF50', fg='white', relief='flat', padx=10, cursor='hand2')
        add_btn.grid(row=0, column=6, padx=8, pady=3)
        
        # 快捷按钮
        quick_frame = tk.Frame(add_frame, bg='#f5f5f5')
        quick_frame.pack(fill=tk.X, pady=(3, 0))
        tk.Label(quick_frame, text="快捷:", font=('微软雅黑', 8), bg='#f5f5f5', fg='#666').pack(side=tk.LEFT, padx=3)
        for days, label in [(30, "30天"), (90, "3月"), (365, "1年"), (3650, "永久")]:
            btn = tk.Button(quick_frame, text=label, command=lambda d=days: self.set_expire_days(d),
                font=('微软雅黑', 8), bg='#E8E8E8', relief='flat', padx=6, cursor='hand2')
            btn.pack(side=tk.LEFT, padx=2)
        
        # 设备列表
        list_frame = tk.Frame(self.parent, bg='#f5f5f5')
        list_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=(0, 10))
        
        columns = ('id', 'expire', 'status', 'note')
        self.tree = ttk.Treeview(list_frame, columns=columns, show='headings', height=10)
        self.tree.heading('id', text='设备ID')
        self.tree.heading('expire', text='过期日期')
        self.tree.heading('status', text='状态')
        self.tree.heading('note', text='备注')
        self.tree.column('id', width=100, anchor='center')
        self.tree.column('expire', width=90, anchor='center')
        self.tree.column('status', width=70, anchor='center')
        self.tree.column('note', width=150, anchor='w')
        
        scrollbar = ttk.Scrollbar(list_frame, orient=tk.VERTICAL, command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        
        # 底部按钮
        btn_frame = tk.Frame(self.parent, bg='#f5f5f5')
        btn_frame.pack(fill=tk.X, padx=10, pady=(0, 10))
        
        tk.Button(btn_frame, text="🗑️ 删除", command=self.delete_device,
            font=('微软雅黑', 9), bg='#f44336', fg='white', relief='flat', padx=10, cursor='hand2').pack(side=tk.LEFT, padx=3)
        tk.Button(btn_frame, text="🔄 刷新", command=self.load_from_gitee,
            font=('微软雅黑', 9), bg='#2196F3', fg='white', relief='flat', padx=10, cursor='hand2').pack(side=tk.LEFT, padx=3)
        
        self.stats_label = tk.Label(btn_frame, text="", font=('微软雅黑', 9), bg='#f5f5f5', fg='#666')
        self.stats_label.pack(side=tk.RIGHT, padx=5)
    
    def set_expire_days(self, days):
        self.expire_entry.delete(0, tk.END)
        self.expire_entry.insert(0, (datetime.now() + timedelta(days=days)).strftime("%Y-%m-%d"))
    
    def load_from_gitee(self):
        self.status_callback("⏳ 正在从 Gitee 加载白名单...", '#FF9800')
        try:
            data, sha = GiteeHelper.load_file("whitelist.json")
            if data:
                self.whitelist_data = data
                self.file_sha = sha
            self.refresh_table()
            self.status_callback("✅ 白名单已同步", '#4CAF50')
        except Exception as e:
            self.status_callback(f"❌ 加载失败: {e}", '#f44336')
    
    def save_to_gitee(self):
        self.status_callback("⏳ 正在同步到 Gitee...", '#FF9800')
        try:
            self.file_sha = GiteeHelper.save_file("whitelist.json", self.whitelist_data, self.file_sha, "更新白名单")
            self.status_callback("✅ 已同步到 Gitee", '#4CAF50')
            return True
        except Exception as e:
            self.status_callback(f"❌ 同步失败: {e}", '#f44336')
            messagebox.showerror("同步失败", str(e))
            return False
    
    def refresh_table(self):
        for item in self.tree.get_children():
            self.tree.delete(item)
        
        active, expired = 0, 0
        for device in self.whitelist_data.get("devices", []):
            device_id = str(device.get("id", ""))
            expire = device.get("expire", "永久")
            note = device.get("note", "")
            
            is_expired = False
            if expire != "永久":
                try:
                    is_expired = datetime.strptime(expire, "%Y-%m-%d") < datetime.now()
                except: pass
            
            status = "❌ 过期" if is_expired else "✅ 有效"
            if is_expired:
                expired += 1
                self.tree.insert('', tk.END, values=(device_id, expire, status, note), tags=('expired',))
            else:
                active += 1
                self.tree.insert('', tk.END, values=(device_id, expire, status, note))
        
        self.tree.tag_configure('expired', foreground='#999')
        self.stats_label.config(text=f"共 {active + expired} 个 | ✅ {active} | ❌ {expired}")
    
    def add_device(self):
        device_id = self.device_id_entry.get().strip().upper()
        expire = self.expire_entry.get().strip()
        note = self.note_entry.get().strip()
        
        if not device_id or not expire:
            messagebox.showwarning("提示", "请填写设备ID和过期日期")
            return
        
        for device in self.whitelist_data.get("devices", []):
            if str(device["id"]).upper() == device_id:
                if messagebox.askyesno("提示", f"设备 {device_id} 已存在，是否更新？"):
                    device["expire"] = expire
                    device["note"] = note
                    if self.save_to_gitee():
                        self.refresh_table()
                        self.clear_inputs()
                return
        
        self.whitelist_data.setdefault("devices", []).append({"id": device_id, "expire": expire, "note": note})
        if self.save_to_gitee():
            self.refresh_table()
            self.clear_inputs()
            messagebox.showinfo("成功", f"已添加设备 {device_id}")
    
    def delete_device(self):
        selected = self.tree.selection()
        if not selected:
            messagebox.showwarning("提示", "请先选择要删除的设备")
            return
        
        device_id = str(self.tree.item(selected[0])['values'][0])
        if not messagebox.askyesno("确认", f"确定要删除设备 {device_id} 吗？"):
            return
        
        self.whitelist_data["devices"] = [d for d in self.whitelist_data.get("devices", []) if str(d["id"]).upper() != device_id.upper()]
        if self.save_to_gitee():
            self.refresh_table()
            messagebox.showinfo("成功", f"已删除设备 {device_id}")
    
    def clear_inputs(self):
        self.device_id_entry.delete(0, tk.END)
        self.note_entry.delete(0, tk.END)


class AnnouncementTab:
    """公告管理标签页（简化版：只用 announcement.json）"""
    
    def __init__(self, parent, status_callback):
        self.parent = parent
        self.status_callback = status_callback
        self.file_sha = None
        self.announcements = []  # 公告列表
        
        self.create_widgets()
        self.load_from_gitee()
    
    def create_widgets(self):
        # 使用 PanedWindow 分为左右两部分
        paned = tk.PanedWindow(self.parent, orient=tk.HORIZONTAL, bg='#f5f5f5', sashwidth=5)
        paned.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        # 左侧：编辑区域
        left_frame = tk.Frame(paned, bg='#f5f5f5')
        paned.add(left_frame, width=380)
        
        edit_frame = tk.LabelFrame(left_frame, text=" 📢 编辑公告 ", font=('微软雅黑', 10, 'bold'),
            fg='#333', bg='#f5f5f5', padx=10, pady=8)
        edit_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        # ID 在发布时自动生成，不需要输入框
        
        # 标题
        row = tk.Frame(edit_frame, bg='#f5f5f5')
        row.pack(fill=tk.X, pady=2)
        tk.Label(row, text="标题:", font=('微软雅黑', 9), bg='#f5f5f5', width=6, anchor='e').pack(side=tk.LEFT)
        self.title_entry = ttk.Entry(row, width=35, font=('微软雅黑', 9))
        self.title_entry.pack(side=tk.LEFT, padx=3, fill=tk.X, expand=True)
        
        # 内容
        row = tk.Frame(edit_frame, bg='#f5f5f5')
        row.pack(fill=tk.BOTH, expand=True, pady=2)
        tk.Label(row, text="内容:", font=('微软雅黑', 9), bg='#f5f5f5', width=6, anchor='ne').pack(side=tk.LEFT, anchor='n')
        self.content_text = tk.Text(row, width=35, height=5, font=('微软雅黑', 9), wrap=tk.WORD)
        self.content_text.pack(side=tk.LEFT, padx=3, fill=tk.BOTH, expand=True)
        
        # 类型
        row = tk.Frame(edit_frame, bg='#f5f5f5')
        row.pack(fill=tk.X, pady=2)
        tk.Label(row, text="类型:", font=('微软雅黑', 9), bg='#f5f5f5', width=6, anchor='e').pack(side=tk.LEFT)
        self.type_var = tk.StringVar(value="info")
        for val, label in [("info", "普通"), ("warning", "警告"), ("important", "重要")]:
            rb = tk.Radiobutton(row, text=label, variable=self.type_var, value=val, 
                font=('微软雅黑', 8), bg='#f5f5f5', activebackground='#f5f5f5')
            rb.pack(side=tk.LEFT, padx=5)
        
        # 只显示一次
        self.show_once_var = tk.BooleanVar(value=True)
        cb = tk.Checkbutton(row, text="只显示一次", variable=self.show_once_var,
            font=('微软雅黑', 8), bg='#f5f5f5', activebackground='#f5f5f5')
        cb.pack(side=tk.RIGHT, padx=5)
        
        # 按钮
        btn_frame = tk.Frame(edit_frame, bg='#f5f5f5')
        btn_frame.pack(fill=tk.X, pady=5)
        
        tk.Button(btn_frame, text="📤 发布公告", command=self.publish,
            font=('微软雅黑', 9, 'bold'), bg='#4CAF50', fg='white', relief='flat', padx=12, cursor='hand2').pack(side=tk.LEFT, padx=2)
        tk.Button(btn_frame, text="🔄 刷新", command=self.load_from_gitee,
            font=('微软雅黑', 9), bg='#2196F3', fg='white', relief='flat', padx=8, cursor='hand2').pack(side=tk.LEFT, padx=2)
        tk.Button(btn_frame, text="🗑️ 清空", command=self.clear_all,
            font=('微软雅黑', 9), bg='#f44336', fg='white', relief='flat', padx=8, cursor='hand2').pack(side=tk.LEFT, padx=2)
        
        # 右侧：公告列表
        right_frame = tk.Frame(paned, bg='#f5f5f5')
        paned.add(right_frame, width=250)
        
        list_frame = tk.LabelFrame(right_frame, text=" 📋 公告列表 ", font=('微软雅黑', 10, 'bold'),
            fg='#333', bg='#f5f5f5', padx=5, pady=5)
        list_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        # 公告列表
        columns = ('date', 'title', 'type')
        self.ann_tree = ttk.Treeview(list_frame, columns=columns, show='headings', height=8)
        self.ann_tree.heading('date', text='日期')
        self.ann_tree.heading('title', text='标题')
        self.ann_tree.heading('type', text='类型')
        self.ann_tree.column('date', width=70, anchor='center')
        self.ann_tree.column('title', width=100, anchor='w')
        self.ann_tree.column('type', width=50, anchor='center')
        self.ann_tree.pack(fill=tk.BOTH, expand=True)
        self.ann_tree.bind('<Double-1>', self.on_double_click)
        
        # 操作按钮
        btn_frame2 = tk.Frame(list_frame, bg='#f5f5f5')
        btn_frame2.pack(fill=tk.X, pady=3)
        tk.Button(btn_frame2, text="👁️ 查看", command=self.view_selected,
            font=('微软雅黑', 8), bg='#2196F3', fg='white', relief='flat', padx=6, cursor='hand2').pack(side=tk.LEFT, padx=2)
        tk.Button(btn_frame2, text="📝 编辑", command=self.edit_selected,
            font=('微软雅黑', 8), bg='#FF9800', fg='white', relief='flat', padx=6, cursor='hand2').pack(side=tk.LEFT, padx=2)
        tk.Button(btn_frame2, text="🗑️ 删除", command=self.delete_selected,
            font=('微软雅黑', 8), bg='#f44336', fg='white', relief='flat', padx=6, cursor='hand2').pack(side=tk.LEFT, padx=2)
    
    def load_from_gitee(self):
        self.status_callback("⏳ 正在加载公告...", '#FF9800')
        try:
            # 只加载 announcement.json
            data, sha = GiteeHelper.load_file("announcement.json")
            if data and isinstance(data, dict):
                self.file_sha = sha
                # 解析公告列表
                if "announcements" in data:
                    self.announcements = data.get("announcements", [])
                elif "id" in data and data.get("id"):
                    # 兼容旧格式单条公告
                    self.announcements = [data]
                else:
                    self.announcements = []
            
            self.refresh_list()
            self.status_callback("✅ 公告已加载", '#4CAF50')
        except Exception as e:
            self.status_callback(f"❌ 加载失败: {e}", '#f44336')
    
    def fill_form(self, ann):
        """填充表单"""
        self.title_entry.delete(0, tk.END)
        self.title_entry.insert(0, ann.get("title", ""))
        self.content_text.delete("1.0", tk.END)
        self.content_text.insert("1.0", ann.get("content", ""))
        self.type_var.set(ann.get("type", "info"))
        self.show_once_var.set(ann.get("showOnce", True))
    
    def refresh_list(self):
        """刷新公告列表"""
        for item in self.ann_tree.get_children():
            self.ann_tree.delete(item)
        
        for idx, ann in enumerate(self.announcements[:20]):
            date = str(ann.get("id", ""))[:8]
            if len(date) == 8 and date.isdigit():
                date = f"{date[:4]}-{date[4:6]}-{date[6:]}"
            else:
                date = str(ann.get("id", ""))[:10]
            title = ann.get("title", "")[:12]
            type_map = {"info": "普通", "warning": "警告", "important": "重要"}
            ann_type = type_map.get(ann.get("type", "info"), "普通")
            self.ann_tree.insert('', tk.END, iid=str(idx), values=(date, title, ann_type))
    
    def get_selected(self):
        """获取选中的公告"""
        selected = self.ann_tree.selection()
        if not selected:
            return None, None
        idx = int(selected[0])
        if idx < len(self.announcements):
            return idx, self.announcements[idx]
        return None, None
    
    def on_double_click(self, event):
        """双击加载公告到表单"""
        idx, ann = self.get_selected()
        if ann:
            self.fill_form(ann)
    
    def edit_selected(self):
        """编辑选中的公告"""
        idx, ann = self.get_selected()
        if not ann:
            messagebox.showwarning("提示", "请先选择要编辑的公告")
            return
        
        self.fill_form(ann)
        messagebox.showinfo("提示", '已加载到编辑区域\n修改后发布即为新公告')
    
    def view_selected(self):
        """查看选中公告内容"""
        idx, ann = self.get_selected()
        if not ann:
            messagebox.showwarning("提示", "请先选择要查看的公告")
            return
        
        content = f"【{ann.get('title', '')}】\n\n"
        content += f"ID: {ann.get('id', '')}\n"
        content += f"类型: {ann.get('type', 'info')}\n"
        content += f"只显示一次: {'是' if ann.get('showOnce', True) else '否'}\n\n"
        content += f"内容:\n{ann.get('content', '')}"
        messagebox.showinfo("公告内容", content)
    
    def delete_selected(self):
        """删除选中的公告"""
        idx, ann = self.get_selected()
        if not ann:
            messagebox.showwarning("提示", "请先选择要删除的公告")
            return
        
        if not messagebox.askyesno("确认", f"确定要删除公告吗？\n\n标题: {ann.get('title', '')}"):
            return
        
        self.status_callback("⏳ 正在删除...", '#FF9800')
        try:
            # 从列表删除
            del self.announcements[idx]
            
            # 保存到 Gitee
            self.file_sha = GiteeHelper.save_file("announcement.json", {"announcements": self.announcements}, self.file_sha, "删除公告")
            
            self.refresh_list()
            self.status_callback("✅ 公告已删除", '#4CAF50')
        except Exception as e:
            self.status_callback(f"❌ 删除失败: {e}", '#f44336')
            messagebox.showerror("删除失败", str(e))
    
    def publish(self):
        """发布新公告"""
        title = self.title_entry.get().strip()
        content = self.content_text.get("1.0", tk.END).strip()
        
        if not title or not content:
            messagebox.showwarning("提示", "请填写标题和内容")
            return
        
        # 自动生成唯一ID
        import time
        ann_id = datetime.now().strftime("%Y%m%d_%H%M%S") + f"_{int(time.time() * 1000) % 1000:03d}"
        
        new_announcement = {
            "id": ann_id,
            "title": title,
            "content": content,
            "type": self.type_var.get(),
            "showOnce": self.show_once_var.get()
        }
        
        self.status_callback("⏳ 正在发布公告...", '#FF9800')
        try:
            # 添加到列表头部
            self.announcements.insert(0, new_announcement)
            
            # 保存到 Gitee
            self.file_sha = GiteeHelper.save_file("announcement.json", {"announcements": self.announcements}, self.file_sha, "发布公告")
            
            self.refresh_list()
            self.status_callback("✅ 公告已发布", '#4CAF50')
            messagebox.showinfo("成功", f"公告已发布！")
            
            # 清空表单
            self.title_entry.delete(0, tk.END)
            self.content_text.delete("1.0", tk.END)
        except Exception as e:
            self.status_callback(f"❌ 发布失败: {e}", '#f44336')
            messagebox.showerror("发布失败", str(e))
    
    def clear_all(self):
        """清空所有公告"""
        if not messagebox.askyesno("确认", "确定要清空所有公告吗？"):
            return
        
        self.status_callback("⏳ 正在清空...", '#FF9800')
        try:
            self.announcements = []
            self.file_sha = GiteeHelper.save_file("announcement.json", {"announcements": []}, self.file_sha, "清空公告")
            
            self.refresh_list()
            self.title_entry.delete(0, tk.END)
            self.content_text.delete("1.0", tk.END)
            self.status_callback("✅ 公告已清空", '#4CAF50')
        except Exception as e:
            self.status_callback(f"❌ 清空失败: {e}", '#f44336')


class MainApp:
    def __init__(self, root):
        self.root = root
        self.root.title("抢课助手管理工具")
        self.root.geometry("650x520")
        self.root.configure(bg="#f5f5f5")
        
        self.create_widgets()
    
    def create_widgets(self):
        # 顶部标题
        title_frame = tk.Frame(self.root, bg="#7B5EA7", height=50)
        title_frame.pack(fill=tk.X)
        title_frame.pack_propagate(False)
        
        tk.Label(title_frame, text="🛠️ 抢课助手管理工具", 
            font=('微软雅黑', 14, 'bold'), fg='white', bg='#7B5EA7').pack(pady=12)
        
        # 底部状态栏（先创建，供 tab 使用）
        self.status_label = tk.Label(self.root, text="就绪", font=('微软雅黑', 9), 
            bg='#E8E8E8', fg='#666', anchor='w', padx=10)
        self.status_label.pack(fill=tk.X, side=tk.BOTTOM)
        
        # 标签页
        notebook = ttk.Notebook(self.root)
        notebook.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        # 设备白名单标签页
        whitelist_frame = tk.Frame(notebook, bg='#f5f5f5')
        notebook.add(whitelist_frame, text=' 📱 设备白名单 ')
        self.whitelist_tab = WhitelistTab(whitelist_frame, self.update_status)
        
        # 公告管理标签页
        announcement_frame = tk.Frame(notebook, bg='#f5f5f5')
        notebook.add(announcement_frame, text=' 📢 公告管理 ')
        self.announcement_tab = AnnouncementTab(announcement_frame, self.update_status)
    
    def update_status(self, text, color='#666'):
        self.status_label.config(text=text, fg=color)
        self.root.update()


def main():
    root = tk.Tk()
    app = MainApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()

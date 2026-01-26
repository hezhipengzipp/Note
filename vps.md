# 📘 VPS 全栈部署与配置速查手册 (个人备忘录)

**更新日期**：2026-01-22
**服务器 IP**：23.94.121.47
**当前域名**：zippsun.us.ci

---

## 1. 基础连接与文件传输

### 远程连接 (SSH)
在本地终端（CMD 或 PowerShell）输入：
ssh root@23.94.121.47

### 上传项目文件 (SCP)
注意：上传前必须确保服务器上已经有了目标文件夹。

# 第一步：在服务器上创建目录
mkdir -p /var/www/html/driveAgent

# 第二步：在本地电脑执行上传（注意路径末尾的斜杠）
scp -r ./dist/* root@23.94.121.47:/var/www/html/driveAgent/

---

## 2. Nginx 核心配置

**配置文件路径**：`/etc/nginx/sites-available/default`
**修改命令**：`sudo nano /etc/nginx/sites-available/default`
**生效命令**：`sudo systemctl restart nginx`

### ✅ 正确的配置代码
适用于 Vue/React 项目，部署在 /driveAgent 子路径下：

server {
listen 80;
server_name zippsun.us.ci www.zippsun.us.ci;

    # 前端文件存放目录
    root /var/www/html;
    index index.html;

    # 1. 根目录禁止访问 (可选)
    location / {
        return 404;
    }

    # 2. 子项目路径 (重点注意：后面不要带斜杠！)
    # ❌ 错误写法：location /driveAgent/
    # ✅ 正确写法：location /driveAgent
    location /driveAgent {
        index index.html;
        # 解决前端路由刷新 404 问题
        try_files $uri $uri/ /driveAgent/index.html;
    }
}

---

## 3. 域名与 Cloudflare 设置

### DNS 解析记录
去 Cloudflare 后台 -> DNS -> Records：
1. 类型: A | 名称: @   | 内容: 23.94.121.47 | 代理状态: 开启 (橙色云朵)
2. 类型: A | 名称: www | 内容: 23.94.121.47 | 代理状态: 开启 (橙色云朵)

### SSL/HTTPS 模式 (关键)
去 Cloudflare 后台 -> SSL/TLS -> Overview：

* **模式 A：Flexible (灵活)**
    * **适用情况**：Certbot 申请证书失败、或者域名被限流（比如现在的 .us.ci）。
    * **原理**：浏览器 <-> HTTPS <-> Cloudflare <-> HTTP <-> VPS。
    * **注意**：此模式下，Nginx 配置里不要写 `return 301 https...`，否则会无限循环重定向。

* **模式 B：Full (完全)**
    * **适用情况**：VPS 上已经成功安装了有效证书。
    * **原理**：全程 HTTPS 加密，更安全。

---

## 4. 常见问题解决方案

### Q1: 访问 `https://.../driveAgent` 报 404？
**原因**：Nginx 配置里的 location 写成了 `/driveAgent/` (多了个斜杠)。
**解决**：去掉斜杠，改成 `/driveAgent`，Nginx 会自动帮你做 301 跳转。

### Q2: Certbot 报错 "Rate Limit"？
**原因**：免费域名 (us.ci) 申请人数太多，触发了 Let's Encrypt 的周限制。
**解决**：放弃在 VPS 上装证书，直接去 Cloudflare 开启 **Flexible** 模式。

---

## 5. Docker 常用命令 (备查)

# 安装 Docker
sudo apt update && sudo apt install docker.io docker-compose -y

# 运行一个 Nginx 容器测试 (端口 8080)
sudo docker run -d -p 8080:80 --name my-test nginx

# 查看正在运行的容器
sudo docker ps

# 停止并删除容器
sudo docker stop my-test
sudo docker rm my-test
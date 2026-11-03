# 上传到 GitHub 的完整步骤

这份文档写给第一次发布模组的人。按顺序做，大约 15 分钟。

---

## 0. 署名与包名

本仓库已经设置好：LICENSE 与 abric.mod.json 的署名是 **xqpeppa**，Java 包名是 io.github.xqpeppa.architect。

如果以后换仓库或换作者，改这三处即可：

- `LICENSE` 第 3 行
- `src/main/resources/fabric.mod.json` 的 `authors` 与 `contact` 链接
- `gradle.properties` 的 `maven_group`（改包名时还要同步改 Java 源码的 `package` 行和目录结构）

改完 abric.mod.json 记得重新构建一次 jar，否则发出去的 jar 里还是旧署名。

---

## 1. 装 Git

Windows 上最省事的两种方式：

```powershell
# 方式一：winget
winget install --id Git.Git -e --source winget

# 方式二：去 https://git-scm.com/download/win 下载安装包
```

装完**重开一个终端**，确认：

```powershell
git --version
git config --global user.name "你的名字"
git config --global user.email "你的邮箱"
```

> 不想折腾命令行的话，装 **GitHub Desktop**（https://desktop.github.com/）也能做完全部流程，界面里点 "Publish repository" 即可。

---

## 2. 在 GitHub 上建一个空仓库

1. 打开 https://github.com/new
2. **Repository name**：`villagearchitect`
3. **Description**：`Fabric mod for Minecraft 1.21.1 — bed-less villagers build their own village out of vanilla structures.`
4. 选 **Public**
5. **不要**勾 "Add a README file"、不要选 .gitignore 和 license（本地已经有了，勾了会冲突）
6. 点 **Create repository**

建完会看到一个空仓库页面，上面有 `https://github.com/你的用户名/villagearchitect.git` 这个地址，复制它。

---

## 3. 把本地代码推上去

在 `villagearchitect` 文件夹里执行（PowerShell 或 Git Bash 都行）：

```powershell
cd C:\Users\31896\Documents\deepseek-harness\default-workspace\villagearchitect

git init
git add .
git commit -m "Village Architect 1.0.0"
git branch -M main
git remote add origin https://github.com/你的用户名/villagearchitect.git
git push -u origin main
```

第一次 push 会弹浏览器让你登录 GitHub，授权一次即可。

> 如果提示 `remote origin already exists`，说明之前加过，用
> `git remote set-url origin https://github.com/你的用户名/villagearchitect.git` 改地址。

推完之后刷新仓库页面，应该能看到 README 渲染出来了。

---

## 4. 构建发布用的 jar

如果你只是想发**已经构建好的这个**，跳过这步，直接用手里那个 `villagearchitect-1.0.0.jar`。

从源码构建：

```powershell
# 需要 JDK 21
# 仓库没带 gradle wrapper，第一次先用 IDEA 打开（会自动生成），或者：
gradle wrapper          # 生成 gradlew / gradlew.bat
.\gradlew.bat build
```

产物：`build/libs/villagearchitect-1.0.0.jar`

> `build/libs/` 里通常还有一个 `-sources.jar`，那是源码包，不用传。

---

## 5. 创建 Release 并挂上 jar

1. 仓库页面右侧 → **Releases** → **Draft a new release**
2. **Choose a tag**：输入 `v1.0.0`，点 "Create new tag on publish"
3. **Release title**：`Village Architect 1.0.0`
4. **Describe this release**：把 `CHANGELOG.md` 里 1.0.0 那段贴进去
5. **Attach binaries**：把 `villagearchitect-1.0.0.jar` 拖进去
6. 点 **Publish release**

发布完，别人点 Releases 就能直接下载 jar，不需要自己编译。

---

## 6. 让仓库更好被搜到（可选，但推荐）

仓库首页右上 **⚙ Settings** 旁边有个 **About** 的编辑按钮：

- **Description**：`Bed-less villagers build their own village out of vanilla structures — Fabric 1.21.1`
- **Topics**：`minecraft` `fabric` `fabric-mod` `minecraft-mod` `villagers` `ai` `1.21.1`

---

## 7. 以后怎么更新

```powershell
# 改完代码、构建、测试之后
git add .
git commit -m "fix: 修掉 xxx"
git push
```

再发新版本时重复第 5 步，tag 换成 `v1.0.1`、`v1.1.0`……

---

## 常见问题

**Q：push 提示 `Support for password authentication was removed`？**
GitHub 不再接受账号密码。用浏览器弹出的授权，或者去 Settings → Developer settings → Personal access tokens 生成一个 token，密码处填 token。

**Q：`git add .` 会不会把 30MB 的构建缓存也传上去？**
不会，`.gitignore` 已经排除了 `build/`、`.gradle/`、`run/`、`dist/`。推之前可以 `git status` 看一眼列表。

**Q：仓库要不要把编译好的 jar 提交进去？**
不建议。jar 放 Release 附件里，仓库只放源码，这样历史干净。

**Q：想同时发到 Modrinth / CurseForge？**
先在这里发 Release，再去 Modrinth 建项目（需要上传 jar + 图标 + 简介 + 支持的 MC 版本），两者可以互相引用。

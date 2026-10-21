// Shizuku UserService 的跨进程接口。
//
// Shizuku 会在「shell 权限」的进程里实例化我们的 RemoteShellService，
// 我们的 App 通过这个接口把命令丢过去、把输出拿回来。
//
// 为什么必须用 AIDL：
//   Shizuku 13 的 API 里，Shizuku.newProcess() 和 ShizukuRemoteProcess 的构造器
//   都是包内私有（private / package-private），App 侧拿不到。
//   官方公开的提权路径只有 bindUserService()，而它要求一个 AIDL 接口。
package com.example.mychat;

interface IRemoteShell {
    /**
     * 执行一条 shell 命令。
     *
     * 返回格式固定为两段，用换行分隔：
     *   第一行 = 退出码（失败时 -1）
     *   后面所有内容 = stdout + stderr 合并后的输出
     */
    String exec(String command);
}

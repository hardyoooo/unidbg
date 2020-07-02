package com.github.unidbg.ios;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.ios.DarwinFileIO;
import com.github.unidbg.file.ios.IOConstants;
import com.github.unidbg.ios.struct.VMStatistics;
import com.github.unidbg.ios.struct.kernel.HostStatisticsReply;
import com.github.unidbg.ios.struct.kernel.HostStatisticsRequest;
import com.github.unidbg.ios.struct.kernel.MachMsgHeader;
import com.github.unidbg.pointer.UnicornPointer;
import com.github.unidbg.pointer.UnicornStructure;
import com.github.unidbg.spi.SyscallHandler;
import com.github.unidbg.unix.UnixEmulator;
import com.github.unidbg.unix.UnixSyscallHandler;
import com.sun.jna.Pointer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

abstract class DarwinSyscallHandler extends UnixSyscallHandler<DarwinFileIO> implements SyscallHandler<DarwinFileIO>, DarwinSyscall  {

    private static final Log log = LogFactory.getLog(DarwinSyscallHandler.class);

    final long bootTime = System.currentTimeMillis();

    protected final int open_NOCANCEL(Emulator<DarwinFileIO> emulator, int offset) {
        RegisterContext context = emulator.getContext();
        Pointer pathname_p = context.getPointerArg(offset);
        int oflags = context.getIntArg(offset + 1);
        int mode = context.getIntArg(offset + 2);
        String pathname = pathname_p.getString(0);
        int fd = open(emulator, pathname, oflags);
        if (log.isDebugEnabled()) {
            log.debug("open_NOCANCEL pathname=" + pathname + ", oflags=0x" + Integer.toHexString(oflags) + ", mode=" + Integer.toHexString(mode) + ", fd=" + fd + ", LR=" + context.getLRPointer());
        }
        return fd;
    }

    protected final int access(Emulator<DarwinFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer pathname = context.getPointerArg(0);
        int mode = context.getIntArg(1);
        String path = pathname.getString(0);
        if (log.isDebugEnabled()) {
            log.debug("access pathname=" + path + ", mode=" + mode);
        }
        return faccessat(emulator, path);
    }

    protected final int faccessat(Emulator<DarwinFileIO> emulator, String pathname) {
        FileResult<?> result = resolve(emulator, pathname, IOConstants.O_RDONLY);
        if (result != null && result.isSuccess()) {
            if (verbose) {
                System.out.println(String.format("File access '%s' from %s", pathname, emulator.getContext().getLRPointer()));
            }
            return 0;
        }

        emulator.getMemory().setErrno(result != null ? result.errno : UnixEmulator.ENOENT);
        if (verbose) {
            System.out.println(String.format("File access failed '%s' from %s", pathname, emulator.getContext().getLRPointer()));
        }
        return -1;
    }

    protected final int listxattr(Emulator<DarwinFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer path = context.getPointerArg(0);
        UnicornPointer namebuf = context.getPointerArg(1);
        int size = context.getIntArg(2);
        int options = context.getIntArg(3);
        String pathname = path.getString(0);
        FileResult<DarwinFileIO> result = resolve(emulator, pathname, IOConstants.O_RDONLY);
        if (namebuf != null) {
            namebuf.setSize(size);
        }
        if (result.isSuccess()) {
            int ret = result.io.listxattr(namebuf, size, options);
            if (ret == -1) {
                log.info("listxattr path=" + pathname + ", namebuf=" + namebuf + ", size=" + size + ", options=" + options + ", LR=" + context.getLRPointer());
            } else {
                if (log.isDebugEnabled()) {
                    log.info("listxattr path=" + pathname + ", namebuf=" + namebuf + ", size=" + size + ", options=" + options + ", LR=" + context.getLRPointer());
                }
            }
            return ret;
        } else {
            log.info("listxattr path=" + pathname + ", namebuf=" + namebuf + ", size=" + size + ", options=" + options + ", LR=" + context.getLRPointer());
            emulator.getMemory().setErrno(UnixEmulator.ENOENT);
            return -1;
        }
    }

    protected final int chmod(Emulator<DarwinFileIO> emulator) {
        RegisterContext context = emulator.getContext();
        Pointer path = context.getPointerArg(0);
        int mode = context.getIntArg(1) & 0xffff;
        String pathname = path.getString(0);
        FileResult<DarwinFileIO> result = resolve(emulator, pathname, IOConstants.O_RDONLY);
        if (result.isSuccess()) {
            int ret = result.io.chmod(mode);
            if (ret == -1) {
                log.info("chmod path=" + pathname + ", mode=0x" + Integer.toHexString(mode));
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("chmod path=" + pathname + ", mode=0x" + Integer.toHexString(mode));
                }
            }
            return ret;
        } else {
            log.info("chmod path=" + pathname + ", mode=0x" + Integer.toHexString(mode));
            emulator.getMemory().setErrno(UnixEmulator.ENOENT);
            return -1;
        }
    }

    protected final boolean host_statistics(Pointer request, MachMsgHeader header) {
        HostStatisticsRequest args = new HostStatisticsRequest(request);
        args.unpack();
        if (log.isDebugEnabled()) {
            log.debug("host_statistics args=" + args);
        }

        if (args.flavor == HostStatisticsRequest.HOST_VM_INFO) {
            int size = UnicornStructure.calculateSize(VMStatistics.class);
            HostStatisticsReply reply = new HostStatisticsReply(request, size);
            reply.unpack();

            header.setMsgBits(false);
            header.msgh_size = header.size() + reply.size();
            header.msgh_remote_port = header.msgh_local_port;
            header.msgh_local_port = 0;
            header.msgh_id += 100; // reply Id always equals reqId+100
            header.pack();

            reply.writeVMStatistics();
            reply.retCode = 0;
            reply.host_info_outCnt = size / 4;
            reply.pack();

            if (log.isDebugEnabled()) {
                log.debug("host_statistics HOST_VM_INFO reply=" + reply);
            }
            return true;
        }

        return false;
    }

}

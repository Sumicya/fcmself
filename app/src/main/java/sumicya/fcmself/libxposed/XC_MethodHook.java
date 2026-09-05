package sumicya.fcmself.libxposed;

import java.lang.reflect.Member;

/**
 * 传统 Xposed 风格的 Hook 回调。
 * 在 {@link #beforeHookedMethod} 中调用 {@link MethodHookParam#setResult} 或
 * {@link MethodHookParam#setThrowable} 可阻止原方法执行（return early），
 * 此时 after 回调仍会执行（按 before 执行顺序的逆序）。
 */
public abstract class XC_MethodHook {

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public boolean isReturnEarly() {
            return returnEarly;
        }

        void resetReturnEarly() {
            returnEarly = false;
        }
    }

    public class Unhook {
        private final Member member;
        private final XC_MethodHook callback;

        Unhook(Member member, XC_MethodHook callback) {
            this.member = member;
            this.callback = callback;
        }

        public void unhook() {
            XposedBridge.unhook(member, callback);
        }
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }
}

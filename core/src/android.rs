//! Android process setup the Kotlin side calls once, before any core use:
//! logcat output and the JVM/Context handle that cpal's AAudio backend
//! reads through `ndk-context`.

use std::ffi::c_void;
use std::sync::Once;

use jni::JNIEnv;
use jni::objects::{JClass, JObject};

static INIT: Once = Once::new();

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_sonata_core_NativeInit_init(
    env: JNIEnv,
    _class: JClass,
    context: JObject,
) {
    INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("SonataCore"),
        );

        let vm = env.get_java_vm().expect("JavaVM");
        let context = env.new_global_ref(context).expect("global Context ref");
        // SAFETY: both pointers stay valid for the life of the process; the
        // global ref is leaked on purpose so the Context is never collected.
        unsafe {
            ndk_context::initialize_android_context(
                vm.get_java_vm_pointer() as *mut c_void,
                context.as_obj().as_raw() as *mut c_void,
            );
        }
        std::mem::forget(context);
        log::info!("android context initialised");
    });
}

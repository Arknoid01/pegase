import sys

from pegase_desktop.bootstrap import (
    configure_panel_backend,
    report_gdk_backend,
    set_prgname,
    setup_debug_logging,
)

setup_debug_logging()
set_prgname()
configure_panel_backend()

from pegase_desktop.gtk_app import run_app  # noqa: E402


def main() -> None:
    from pegase_desktop.single_instance import force_stop_existing, try_quit_existing

    if "--quit" in sys.argv:
        try_quit_existing()
        return
    if "--replace" in sys.argv:
        force_stop_existing()
    report_gdk_backend()
    run_app(toggle="--toggle" in sys.argv)


if __name__ == "__main__":
    main()

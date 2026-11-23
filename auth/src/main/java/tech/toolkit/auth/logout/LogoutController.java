package tech.toolkit.auth.logout;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The page shown after logout when the client did not name an address to return to. */
@Controller
class LogoutController {

  @GetMapping("/logged-out")
  String loggedOut() {
    return "logged-out";
  }
}

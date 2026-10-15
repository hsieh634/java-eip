package com.renewflow.eip.leave;

import com.renewflow.eip.identity.EmployeeUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.renewflow.eip.leave.LeaveWorkflowDtos.*;

@RestController
@RequestMapping("/api/admin/leave/workflow")
public class LeaveWorkflowController {
    private final LeaveWorkflowService service;
    LeaveWorkflowController(LeaveWorkflowService service) { this.service=service; }
    @GetMapping public Settings settings(@AuthenticationPrincipal EmployeeUserDetails actor) { return service.settings(actor); }
    @PostMapping("/drafts") public Operation create(@AuthenticationPrincipal EmployeeUserDetails actor,@Valid @RequestBody DraftRequest body) { return service.save(actor,null,body); }
    @PutMapping("/drafts/{id}") public Operation save(@AuthenticationPrincipal EmployeeUserDetails actor,@PathVariable long id,@Valid @RequestBody DraftRequest body) { return service.save(actor,id,body); }
    @PostMapping("/drafts/{id}/publish") public Operation publish(@AuthenticationPrincipal EmployeeUserDetails actor,@PathVariable long id,@Valid @RequestBody PublishRequest body) { return service.publish(actor,id,body); }
    @GetMapping("/operations/{key}") public Operation operation(@AuthenticationPrincipal EmployeeUserDetails actor,@PathVariable UUID key) { return service.operation(actor,key); }
    @GetMapping("/preview") public Preview preview(@AuthenticationPrincipal EmployeeUserDetails actor,@RequestParam long employeeId,@RequestParam(required=false) Long draftId) { return service.preview(actor,employeeId,draftId); }
}

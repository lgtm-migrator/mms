package org.openmbee.sdvc.crud.controllers.branches;

import java.util.ArrayList;
import java.util.List;
import javax.transaction.Transactional;
import org.openmbee.sdvc.core.config.Privileges;
import org.openmbee.sdvc.core.objects.BranchesResponse;
import org.openmbee.sdvc.core.security.CustomMSERoot;
import org.openmbee.sdvc.crud.controllers.BaseController;
import org.openmbee.sdvc.crud.exceptions.NotFoundException;
import org.openmbee.sdvc.crud.services.BranchService;
import org.openmbee.sdvc.json.RefJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/projects/{projectId}/refs")
@Transactional
public class BranchesGet extends BaseController {

    private BranchService branchService;

    @Autowired
    public BranchesGet(BranchService branchService) {
        this.branchService = branchService;
    }

    @GetMapping(value = {"", "/{refId}"})
    @PreAuthorize("#refId == null || hasBranchPrivilege(#projectId, #refId, 'BRANCH_READ', true)")
    public ResponseEntity<?> handleRequest(
        @PathVariable String projectId,
        @PathVariable(required = false) String refId,
        Authentication auth) {

        if (refId != null) {
            BranchesResponse res = branchService.getBranch(projectId, refId);
            if (res.getBranches().isEmpty()) {
               throw new NotFoundException(res.addMessage("Not found"));
            }
            return ResponseEntity.ok(res);
        } else {
            BranchesResponse res = branchService.getBranches(projectId);
            if (!permissionService.isProjectPublic(projectId)) {
                rejectAnonymous(auth);
                List<RefJson> filtered = new ArrayList<>();
                for (RefJson ref: res.getBranches()) {
                    if (permissionService.hasBranchPrivilege(Privileges.BRANCH_READ.name(), auth.getName(),
                            CustomMSERoot.getGroups(auth), projectId, ref.getId())) {
                        filtered.add(ref);
                    }
                }
                res.setBranches(filtered);
            }
            return ResponseEntity.ok(res);
        }
    }
}

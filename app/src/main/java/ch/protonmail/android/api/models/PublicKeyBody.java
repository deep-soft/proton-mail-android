/*
 * Copyright (c) 2022 Proton AG
 *
 * This file is part of Proton Mail.
 *
 * Proton Mail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Mail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Mail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.api.models;

import com.google.gson.annotations.SerializedName;

import java.util.Set;

import ch.protonmail.android.api.models.enumerations.KeyFlag;
import ch.protonmail.android.api.utils.Fields;

public class PublicKeyBody extends ResponseBody {
    @SerializedName(Fields.Keys.KeyBody.FLAGS)
    private int flags;
    @SerializedName(Fields.Keys.KeyBody.PUBLIC_KEY)
    private String publicKey;

    private Set<KeyFlag> flagSet;

    public PublicKeyBody(int flags, String publicKey) {
        this.flags = flags;
        this.publicKey = publicKey;
    }

    private void ensureFlagSet() {
        if (flagSet == null) {
            flagSet = KeyFlag.fromInteger(flags);
        }
    }

    public boolean isAllowedForSending() {
        ensureFlagSet();
        return flagSet.contains(KeyFlag.ENCRYPTION_ENABLED);
    }

    public boolean isAllowedForVerification() {
        ensureFlagSet();
        return flagSet.contains(KeyFlag.VERIFICATION_ENABLED);
    }

    public String getPublicKey() {
        return publicKey;
    }
}
